package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP on a real port: the runner talks to this same app over loopback. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "settlement-engine.demo.load.cooldown-seconds=2")
class LabLoadIntegrationTest extends AbstractLabIntegrationTest {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    LabResetService reset;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LoadRunGate gate;

    @BeforeEach
    void freshSeedAndNoActiveRun() throws Exception {
        for (int i = 0; i < 100 && gate.activeRunId() != null; i++) {
            Thread.sleep(200);
        }
        Thread.sleep(2200); // cooldown between runs
        reset.reset();
    }

    private ResponseEntity<String> start(String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/lab/load/start", new HttpEntity<>(body, h), String.class);
    }

    private JsonNode awaitDone(String runId) throws Exception {
        for (int i = 0; i < 300; i++) {
            JsonNode run = mapper.readTree(rest.getForObject("/lab/load/" + runId, String.class));
            if (!run.get("status").asText().equals("RUNNING")) {
                return run;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("run did not finish");
    }

    private static String plan(String scenario, int vus, int seconds, int total, String amount, String fault) {
        return "{\"scenario\":\"" + scenario + "\",\"virtualUsers\":" + vus + ",\"durationSeconds\":" + seconds
                + ",\"totalRequests\":" + total + ",\"amount\":" + amount + ",\"seed\":42"
                + (fault == null ? "" : ",\"fault\":" + fault) + "}";
    }

    private void assertAllInvariantsPass(JsonNode run) {
        run.get("invariants").forEach(i -> assertThat(i.get("status").asText())
                .as(i.get("id").asText() + ": " + i.get("detail").asText()).isNotEqualTo("FAIL"));
        assertThat(run.get("verdict").asText()).isEqualTo("PASS");
    }

    @Test
    void aSmallFreshSettlementsPlanPassesAllInvariantsWithMeasuredNumbers() throws Exception {
        ResponseEntity<String> started = start(plan("FRESH_SETTLEMENTS", 3, 3, 60, "1.00", null));
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode run = awaitDone(mapper.readTree(started.getBody()).get("runId").asText());

        assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
        assertAllInvariantsPass(run);
        JsonNode summary = run.get("summary");
        assertThat(summary.get("totalRequests").asLong()).isBetween(1L, 60L);
        assertThat(summary.get("p50Ms").asDouble()).isGreaterThan(0.0);
        assertThat(summary.get("statusCounts").get("201").asLong()).isGreaterThan(0L);
        assertThat(run.get("samples").size()).isGreaterThanOrEqualTo(2);
        assertThat(run.get("samples").get(0).has("hikariActive")).isTrue();
        assertThat(run.get("k6Equivalent").asText()).contains("k6 run load/settlement-load-test.js");
    }

    @Test
    void theDuplicateKeyBurstMovesMoneyExactlyOnce() throws Exception {
        JsonNode run = awaitDone(mapper.readTree(start(plan("DUPLICATE_KEY_BURST", 8, 3, 40, "50.00", null)).getBody())
                .get("runId").asText());
        assertAllInvariantsPass(run);
        JsonNode dup = null;
        for (JsonNode i : run.get("invariants")) {
            if (i.get("id").asText().equals("DUPLICATE_KEY_MOVED_ONCE")) {
                dup = i;
            }
        }
        assertThat(dup.get("status").asText()).isEqualTo("PASS");
        assertThat(dup.get("detail").asText()).contains("1 settlement row(s)");
        assertThat(jdbc.queryForObject("select balance from ledger_accounts where id = '20000000-0000-0000-0000-000000000001'",
                java.math.BigDecimal.class)).isEqualByComparingTo("950.00");
    }

    @Test
    void driftThatExistedBeforeTheRunIsReportedAsSuchNotBlamedOnTheRun() throws Exception {
        jdbc.update("update ledger_accounts set balance = balance + 40 where id = '10000000-0000-0000-0000-000000000001'");
        JsonNode run = awaitDone(mapper.readTree(start(plan("FRESH_SETTLEMENTS", 3, 3, 40, "1.00", null)).getBody())
                .get("runId").asText());
        JsonNode balances = null;
        for (JsonNode i : run.get("invariants")) {
            if (i.get("id").asText().equals("BALANCES_MATCH_ENTRIES")) {
                balances = i;
            }
        }
        assertThat(balances.get("status").asText()).as(balances.get("detail").asText()).isEqualTo("PASS");
        assertThat(balances.get("detail").asText()).contains("already drifted before the run").contains("40");
    }

    @Test
    void theInvoiceFinancingScenarioRuns() throws Exception {
        JsonNode run = awaitDone(mapper.readTree(start(plan("INVOICE_FINANCING", 2, 3, 20, "100.00", null)).getBody())
                .get("runId").asText());
        assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.get("summary").get("outcomeCounts").has("FINANCED")).isTrue();
        assertAllInvariantsPass(run);
    }

    @Test
    void injectedResponseLossesProduceUnknownsReportedAsStrandedNotAsInvariantFailures() throws Exception {
        String fault = "{\"requestLost\":0.0,\"responseLost\":0.3,\"declined\":0.1,\"slow\":0.0,\"slowMs\":0}";
        JsonNode run = awaitDone(mapper.readTree(start(plan("FRESH_SETTLEMENTS", 4, 4, 100, "1.00", fault)).getBody())
                .get("runId").asText());
        assertThat(run.get("summary").get("outcomeCounts").get("UNKNOWN").asLong()).isGreaterThan(0L);
        assertThat(run.get("stranded").get("unknownOutcomes").asLong()).isGreaterThan(0L);
        assertThat(run.get("stranded").get("explanation").asText()).contains("Known gap 1");
        assertThat(run.get("summary").get("outcomeCounts").has("FAILED")).isTrue();
        assertAllInvariantsPass(run);
    }

    @Test
    void valuesAboveTheCapsAreRefusedByTheServer() {
        for (String body : new String[] {
                plan("FRESH_SETTLEMENTS", 21, 3, 60, "1.00", null),
                plan("FRESH_SETTLEMENTS", 3, 31, 60, "1.00", null),
                plan("FRESH_SETTLEMENTS", 3, 3, 5001, "1.00", null),
                plan("FRESH_SETTLEMENTS", 3, 3, 60, "100.01", null)}) {
            assertThat(start(body).getStatusCode()).as(body).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(start("{\"scenario\":\"NOPE\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onlyOneRunAtATimeThenACooldownThenCancelWorks() throws Exception {
        ResponseEntity<String> first = start(plan("FRESH_SETTLEMENTS", 2, 10, 200, "1.00", null));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String runId = mapper.readTree(first.getBody()).get("runId").asText();

        assertThat(start(plan("FRESH_SETTLEMENTS", 2, 3, 20, "1.00", null)).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rest.postForEntity("/lab/reset", null, String.class).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        rest.postForEntity("/lab/load/" + runId + "/cancel", null, String.class);
        JsonNode done = awaitDone(runId);
        assertThat(done.get("status").asText()).isEqualTo("CANCELLED");

        assertThat(start(plan("FRESH_SETTLEMENTS", 2, 3, 20, "1.00", null)).getStatusCode())
                .as("inside the cooldown").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void historyKeepsTheLastRunsAndAResetClearsThem() throws Exception {
        JsonNode run = awaitDone(mapper.readTree(start(plan("FRESH_SETTLEMENTS", 2, 2, 10, "1.00", null)).getBody())
                .get("runId").asText());
        JsonNode history = mapper.readTree(rest.getForObject("/lab/load/history", String.class));
        assertThat(history.size()).isBetween(1, 10);
        assertThat(history.get(0).get("runId").asText()).isEqualTo(run.get("runId").asText());

        Thread.sleep(2200);
        reset.reset();
        assertThat(mapper.readTree(rest.getForObject("/lab/load/history", String.class))).isEmpty();
    }

    @Test
    void theResultIsDownloadableAsJson() throws Exception {
        JsonNode run = awaitDone(mapper.readTree(start(plan("FRESH_SETTLEMENTS", 2, 2, 10, "1.00", null)).getBody())
                .get("runId").asText());
        ResponseEntity<String> download = rest.getForEntity("/lab/load/" + run.get("runId").asText() + "?download=true", String.class);
        assertThat(download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment").contains(".json");
    }
}
