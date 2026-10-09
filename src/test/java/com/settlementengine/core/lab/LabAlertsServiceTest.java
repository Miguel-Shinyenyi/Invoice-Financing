package com.settlementengine.core.lab;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LabAlertsServiceTest {

    static final String RULES = """
            {"status":"success","data":{"groups":[{"name":"invoice-financing","rules":[
              {"name":"BackendDown","query":"up{job=\\"backend\\"} == 0","duration":15,"state":"inactive","health":"ok","type":"alerting","alerts":[]},
              {"name":"MLServiceDown","query":"up{job=\\"ml-service\\"} == 0","duration":15,"state":"firing","health":"ok","type":"alerting",
               "alerts":[{"state":"firing","activeAt":"2026-10-09T12:00:00Z","labels":{"alertname":"MLServiceDown"}}]},
              {"name":"SettlementFailureRateSpike","query":"rate(x[5m]) > 0.5","duration":30,"state":"pending","health":"ok","type":"alerting","alerts":[]}
            ]}]}}
            """;

    static final String ALERTS = """
            [{"labels":{"alertname":"MLServiceDown","severity":"warning"},"annotations":{"summary":"ml down"},
              "startsAt":"2026-10-09T12:00:15Z","status":{"state":"active"}}]
            """;

    @Test
    void aBoardOfTheThreeRealRulesWithStateSandboxAndRealDurationsPlusTheFixedGapRow() throws Exception {
        Path rules = Files.createTempFile("alert-rules", ".yml");
        Files.writeString(rules, """
                groups:
                  - name: invoice-financing
                    rules:
                      - alert: BackendDown
                        expr: up{job="backend"} == 0
                        for: 1m
                      - alert: MLServiceDown
                        expr: up{job="ml-service"} == 0
                        for: 1m
                      - alert: SettlementFailureRateSpike
                        expr: rate(x[5m]) > 0.5
                        for: 5m
                """);
        try (StubUpstream prom = new StubUpstream().on("/api/v1/rules", RULES);
             StubUpstream am = new StubUpstream().on("/api/v2/alerts", ALERTS)) {
            LabProperties props = LabPropertiesFixtures.defaults().withPrometheus(prom.url(), am.url(), rules.toString());
            Map<String, Object> board = new LabAlertsService(props).board();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) board.get("rules");
            assertThat(rows).hasSize(4);
            assertThat(rows.get(0)).containsEntry("name", "BackendDown").containsEntry("state", "inactive")
                    .containsEntry("sandboxForSeconds", 15L).containsEntry("realForSeconds", 60L);
            assertThat(rows.get(1)).containsEntry("name", "MLServiceDown").containsEntry("state", "firing");
            assertThat(rows.get(2)).containsEntry("state", "pending").containsEntry("realForSeconds", 300L);
            assertThat(rows.get(3)).containsEntry("name", "Open reconciliation mismatches")
                    .containsEntry("state", "no rule exists");
            assertThat((String) rows.get(3).get("note")).contains("Known gap 6");
            assertThat((List<?>) board.get("alertmanager")).hasSize(1);
            assertThat(prom.requests).containsExactly("GET /api/v1/rules");
            assertThat(am.requests).containsExactly("GET /api/v2/alerts");
        }
    }

    @Test
    void whenPrometheusIsDownTheBoardSaysSoInsteadOfInventingStates() {
        Map<String, Object> board = new LabAlertsService(LabPropertiesFixtures.defaults()
                .withPrometheus("http://127.0.0.1:1", "http://127.0.0.1:1", "/nonexistent")).board();
        assertThat(board.get("available")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) board.get("rules");
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).get("state")).isEqualTo("unknown");
    }
}
