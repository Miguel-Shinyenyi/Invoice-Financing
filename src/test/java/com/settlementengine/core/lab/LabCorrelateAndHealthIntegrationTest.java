package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class LabCorrelateAndHealthIntegrationTest extends AbstractLabIntegrationTest {

    static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    static StubUpstream jaeger;
    static StubUpstream ml;
    static StubUpstream prometheus;

    static {
        try {
            jaeger = new StubUpstream().on("/api/traces/" + TRACE_ID, LabJaegerServiceTest.TRACE_JSON).on("/api/services", "{\"data\":[]}");
            ml = new StubUpstream().on("/health", "{\"status\":\"ok\"}");
            prometheus = new StubUpstream().on("/-/ready", "{}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry r) {
        r.add("settlement-engine.demo.upstreams.jaeger-url", jaeger::url);
        r.add("settlement-engine.demo.upstreams.ml-url", ml::url);
        r.add("settlement-engine.demo.upstreams.prometheus-url", prometheus::url);
        r.add("settlement-engine.demo.upstreams.alertmanager-url", () -> "http://127.0.0.1:1");
    }

    @AfterAll
    static void stop() {
        jaeger.close();
        ml.close();
        prometheus.close();
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    LabResetService reset;
    @Autowired
    LabLogBuffer logs;

    @BeforeEach
    void seed() {
        reset.reset();
    }

    private JsonNode getJson(String uri) throws Exception {
        MvcResult r = mvc.perform(get(uri)).andReturn();
        assertThat(r.getResponse().getStatus()).as(uri + " " + r.getResponse().getContentAsString()).isEqualTo(200);
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void correlateReturnsLogsTracesOutboxAndTheInspectorForOneSettlementRequest() throws Exception {
        String rid = "corr-settle-1";
        MvcResult created = mvc.perform(post("/lab/settlements").header("X-Request-Id", rid)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceAccountId\":\"10000000-0000-0000-0000-000000000001\","
                        + "\"destinationAccountId\":\"10000000-0000-0000-0000-000000000002\",\"amount\":4.00}")).andReturn();
        String settlementId = mapper.readTree(created.getResponse().getContentAsString()).get("settlementId").asText();
        // the OpenTelemetry agent is not present under Maven: give one of this request's log lines a trace id the way it would
        logs.append(new LabLogEvent(0, Instant.now(), "backend", "INFO", "test", "traced line", rid, settlementId, null, TRACE_ID));

        JsonNode c = getJson("/lab/correlate?requestId=" + rid);
        assertThat(c.get("request").get("path").asText()).isEqualTo("/lab/settlements");
        assertThat(c.get("request").get("status").asInt()).isEqualTo(200);
        assertThat(c.get("logs").size()).isGreaterThan(0);
        c.get("logs").forEach(l -> assertThat(l.get("requestId").asText()).isEqualTo(rid));
        assertThat(c.get("settlementIds").get(0).asText()).isEqualTo(settlementId);
        assertThat(c.get("traces").get("available").asBoolean()).isTrue();
        assertThat(c.get("traces").get("items").get(0).get("traceId").asText()).isEqualTo(TRACE_ID);
        assertThat(c.get("outbox").size()).isGreaterThanOrEqualTo(2);
        assertThat(c.get("inspector").get("settlement").get("id").asText()).isEqualTo(settlementId);
    }

    @Test
    void correlateFindsTheAuditRowOfARealApiCallByActorAndTimeWindow() throws Exception {
        String token = mapper.readTree(mvc.perform(post("/lab/personas/READ_ONLY/token")).andReturn().getResponse()
                .getContentAsString()).get("token").asText();
        String rid = "corr-audit-1";
        mvc.perform(get("/accounts/40000000-0000-0000-0000-000000000001").header("Authorization", "Bearer " + token)
                .header("X-Request-Id", rid));
        JsonNode c = getJson("/lab/correlate?requestId=" + rid);
        assertThat(c.get("request").get("actor").asText()).isEqualTo("lab-readonly");
        assertThat(c.get("audit").size()).isGreaterThanOrEqualTo(1);
        assertThat(c.get("audit").get(0).get("action").asText()).isEqualTo("GET_ACCOUNT");
        assertThat(c.get("audit").get(0).get("matchedBy").asText()).contains("time window");
    }

    @Test
    void correlateValidatesTheRequestIdAndHandlesAnUnknownOne() throws Exception {
        assertThat(mvc.perform(get("/lab/correlate?requestId=a b;c")).andReturn().getResponse().getStatus()).isEqualTo(400);
        JsonNode none = getJson("/lab/correlate?requestId=never-seen");
        assertThat(none.get("logs")).isEmpty();
        assertThat(none.get("request").isNull()).isTrue();
    }

    @Test
    void recentRequestsAreListedForThePicker() throws Exception {
        mvc.perform(post("/lab/sweep/run").header("X-Request-Id", "picker-1"));
        JsonNode recent = getJson("/lab/requests");
        boolean found = false;
        for (JsonNode r : recent) {
            found |= r.get("requestId").asText().equals("picker-1");
        }
        assertThat(found).isTrue();
    }

    @Test
    void healthReportsEachDependencyWithStatusLatencyAndTimeOfLastCheck() throws Exception {
        JsonNode h = getJson("/lab/health");
        for (String name : new String[] {"backend", "ml-service", "postgres", "kafka", "jaeger", "prometheus", "alertmanager"}) {
            JsonNode c = null;
            for (JsonNode x : h.get("checks")) {
                if (x.get("name").asText().equals(name)) {
                    c = x;
                }
            }
            assertThat(c).as(name).isNotNull();
            assertThat(c.get("status").asText()).isIn("UP", "DOWN");
            assertThat(c.get("latencyMs").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(c.get("checkedAt").asText()).isNotBlank();
        }
        for (String up : new String[] {"backend", "ml-service", "postgres", "kafka", "jaeger", "prometheus"}) {
            for (JsonNode x : h.get("checks")) {
                if (x.get("name").asText().equals(up)) {
                    assertThat(x.get("status").asText()).as(up + " " + x).isEqualTo("UP");
                }
            }
        }
        for (JsonNode x : h.get("checks")) {
            if (x.get("name").asText().equals("alertmanager")) {
                assertThat(x.get("status").asText()).isEqualTo("DOWN");
            }
        }
    }

    @Test
    void theTracesEndpointsRefuseHostileInput() throws Exception {
        assertThat(mvc.perform(get("/lab/traces/..%2Fadmin")).andReturn().getResponse().getStatus()).isIn(400, 404);
        assertThat(mvc.perform(get("/lab/traces/not-hex")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get("/lab/traces?requestId=a%26service%3Devil")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(getJson("/lab/traces/" + TRACE_ID).get("spanCount").asInt()).isEqualTo(5);
    }
}
