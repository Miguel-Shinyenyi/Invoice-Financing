package com.settlementengine.core.lab;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The sandbox and staging must run the same alert rules. The canonical file and staging's inline copy cannot drift. */
class AlertRulesSyncTest {

    @SuppressWarnings("unchecked")
    private static Object canonicalRules() throws Exception {
        Map<String, Object> doc = new Yaml().load(Files.readString(Path.of("infra/monitoring/alert-rules.yml")));
        return doc.get("groups");
    }

    @SuppressWarnings("unchecked")
    private static Object stagingInlineRules() throws Exception {
        for (Object d : new Yaml().loadAll(Files.readString(Path.of("infra/k8s/08-prometheus.yaml")))) {
            Map<String, Object> doc = (Map<String, Object>) d;
            if (doc != null && "ConfigMap".equals(doc.get("kind"))
                    && "prometheus-config".equals(((Map<String, Object>) doc.get("metadata")).get("name"))) {
                String rules = (String) ((Map<String, Object>) doc.get("data")).get("rules.yml");
                return ((Map<String, Object>) new Yaml().load(rules)).get("groups");
            }
        }
        throw new AssertionError("prometheus-config ConfigMap not found in 08-prometheus.yaml");
    }

    @Test
    void stagingsInlineRulesAreIdenticalToTheCanonicalFile() throws Exception {
        assertThat(stagingInlineRules()).isEqualTo(canonicalRules());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCanonicalFileHoldsTheThreeRealRules() throws Exception {
        List<Map<String, Object>> groups = (List<Map<String, Object>>) canonicalRules();
        List<Map<String, Object>> rules = (List<Map<String, Object>>) groups.get(0).get("rules");
        assertThat(rules).extracting(r -> r.get("alert")).containsExactly("BackendDown", "MLServiceDown", "SettlementFailureRateSpike");
        assertThat(LabAlertsService.REAL_RULES).containsExactly("BackendDown", "MLServiceDown", "SettlementFailureRateSpike");
    }
}
