package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class LabObservabilityIntegrationTest extends AbstractLabIntegrationTest {

    static final String A = "10000000-0000-0000-0000-000000000001";
    static final String B = "10000000-0000-0000-0000-000000000002";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    LabResetService reset;

    @BeforeEach
    void freshSeed() {
        reset.reset();
    }

    private JsonNode getJson(String uri) throws Exception {
        MvcResult r = mvc.perform(get(uri)).andReturn();
        assertThat(r.getResponse().getStatus()).as(uri + " " + r.getResponse().getContentAsString()).isEqualTo(200);
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private String settle(String amount) throws Exception {
        MvcResult r = mvc.perform(post("/lab/settlements").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"sourceAccountId\":\"" + A
                        + "\",\"destinationAccountId\":\"" + B + "\",\"amount\":" + amount + "}")).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("settlementId").asText();
    }

    @Test
    void logsCanBeFilteredBySettlementIdAndLevelAndText() throws Exception {
        String id = settle("2.00");
        JsonNode bySettlement = getJson("/lab/logs?settlementId=" + id);
        assertThat(bySettlement.get("events").size()).isGreaterThan(0);
        bySettlement.get("events").forEach(e -> assertThat(e.get("settlementId").asText()).isEqualTo(id));

        assertThat(getJson("/lab/logs?settlementId=" + id + "&text=gateway call").get("events").size()).isEqualTo(1);
        assertThat(getJson("/lab/logs?settlementId=" + id + "&level=ERROR").get("events")).isEmpty();
        assertThat(getJson("/lab/logs?loggerPrefix=com.settlementengine.core.lab").get("events").size()).isGreaterThan(0);
    }

    @Test
    void logsCarryTheRequestIdFromTheRequestIdFilter() throws Exception {
        MvcResult r = mvc.perform(post("/lab/settlements").header("X-Request-Id", "req-abc-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceAccountId\":\"" + A + "\",\"destinationAccountId\":\"" + B + "\",\"amount\":1.00}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson("/lab/logs?requestId=req-abc-123").get("events").size()).isGreaterThan(0);
    }

    @Test
    void theLimitIsCappedAt500AndTheRingAt2000() throws Exception {
        assertThat(getJson("/lab/logs?limit=100000").get("events").size()).isLessThanOrEqualTo(500);
        assertThat(getJson("/lab/logs").get("capacity").asInt()).isEqualTo(2000);
    }

    @Test
    void metricsExposeTheCuratedSnapshotFromTheRegistry() throws Exception {
        settle("3.00");
        JsonNode m = getJson("/lab/metrics");
        assertThat(m.get("settlementOutcome").get("CONFIRMED").asLong()).isGreaterThan(0L);
        assertThat(m.get("counters").has("finalizeRetries")).isTrue();
        assertThat(m.get("counters").has("unknownFallbacks")).isTrue();
        assertThat(m.get("hikari").has("active")).isTrue();
        assertThat(m.get("hikari").has("idle")).isTrue();
        assertThat(m.get("hikari").has("pending")).isTrue();
        assertThat(m.get("jvm").get("threadsLive").asInt()).isGreaterThan(0);
        assertThat(m.get("outbox").has("pending")).isTrue();
        assertThat(m.get("readModel").has("lag")).isTrue();
        assertThat(m.get("dashboards").get("fraudDecisions").get("query").asText()).contains("FROM fraud_assessments");
        assertThat(m.get("dashboards").get("settlementOutcomes").get("query").asText()).contains("settlement_outcome_total");
    }

    @Test
    void thePrometheusEndpointIsNotProxiedUnderLab() throws Exception {
        assertThat(mvc.perform(get("/lab/actuator/prometheus")).andReturn().getResponse().getStatus()).isIn(404, 405);
        assertThat(mvc.perform(get("/lab/metrics/prometheus")).andReturn().getResponse().getStatus()).isIn(404, 405);
    }

    @Test
    void eventsListTheSixTopicsFromKafkaTopicsWithPendingAndPublishedCounts() throws Exception {
        JsonNode e = getJson("/lab/events");
        List<String> topics = new ArrayList<>();
        e.get("topics").forEach(t -> topics.add(t.get("topic").asText()));
        assertThat(topics).containsExactlyInAnyOrder("settlement.requested", "settlement.confirmed", "settlement.failed",
                "settlement.unknown", "reconciliation.mismatch_found", "reconciliation.resolved");
        e.get("topics").forEach(t -> {
            assertThat(t.has("pending")).isTrue();
            assertThat(t.has("published")).isTrue();
        });
        assertThat(e.get("recent").size()).isGreaterThan(0);
        assertThat(e.get("recent").get(0).has("createdAt")).isTrue();
        assertThat(e.get("readModel").has("lag")).isTrue();
    }

    @Test
    void theMismatchFoundTopicIsListedWithNoConsumerAsKnownGap6() throws Exception {
        JsonNode e = getJson("/lab/events");
        for (JsonNode t : e.get("topics")) {
            if (t.get("topic").asText().equals("reconciliation.mismatch_found")) {
                assertThat(t.get("knownGap").asText()).contains("No consumer");
            }
        }
    }
}
