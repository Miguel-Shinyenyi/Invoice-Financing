package com.settlementengine.core.lab;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabJaegerServiceTest {

    static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    static final String TRACE_JSON = """
            {"data":[{"traceID":"4bf92f3577b34da6a3ce929d0e0e4736","processes":{
              "p1":{"serviceName":"settlement-engine-backend"},"p2":{"serviceName":"fraud-detection-ml"}},
             "spans":[
              {"traceID":"4bf92f3577b34da6a3ce929d0e0e4736","spanID":"a1","operationName":"POST /settlements","references":[],
               "startTime":1000000,"duration":50000,"processID":"p1",
               "tags":[{"key":"http.request.method","type":"string","value":"POST"},{"key":"span.kind","type":"string","value":"server"}]},
              {"traceID":"4bf92f3577b34da6a3ce929d0e0e4736","spanID":"a2","operationName":"INSERT settlements","references":[{"refType":"CHILD_OF","spanID":"a1"}],
               "startTime":1005000,"duration":3000,"processID":"p1",
               "tags":[{"key":"db.system","type":"string","value":"postgresql"},{"key":"db.statement","type":"string","value":"insert into settlements ..."}]},
              {"traceID":"4bf92f3577b34da6a3ce929d0e0e4736","spanID":"a3","operationName":"settlement.requested publish","references":[{"refType":"CHILD_OF","spanID":"a1"}],
               "startTime":1010000,"duration":2000,"processID":"p1",
               "tags":[{"key":"messaging.system","type":"string","value":"kafka"}]},
              {"traceID":"4bf92f3577b34da6a3ce929d0e0e4736","spanID":"a4","operationName":"POST","references":[{"refType":"CHILD_OF","spanID":"a1"}],
               "startTime":1020000,"duration":8000,"processID":"p1",
               "tags":[{"key":"http.request.method","type":"string","value":"POST"},{"key":"url.full","type":"string","value":"http://ml/score"},{"key":"span.kind","type":"string","value":"client"}]},
              {"traceID":"4bf92f3577b34da6a3ce929d0e0e4736","spanID":"b1","operationName":"POST /score","references":[{"refType":"CHILD_OF","spanID":"a4"}],
               "startTime":1021000,"duration":5000,"processID":"p2",
               "tags":[{"key":"span.kind","type":"string","value":"server"},{"key":"error","type":"bool","value":true}]}
             ]}]}
            """;

    private LabJaegerService service(StubUpstream stub, LabLogBuffer logs) {
        return new LabJaegerService(LabPropertiesFixtures.defaults().withJaeger(stub.url()), logs);
    }

    @Test
    void aTraceIsRenderedAsASpanWaterfallWithServiceOperationDurationAndKinds() throws Exception {
        try (StubUpstream stub = new StubUpstream().on("/api/traces/" + TRACE_ID, TRACE_JSON)) {
            LabJaegerService.TraceView trace = service(stub, new LabLogBuffer()).trace(TRACE_ID);
            assertThat(trace.traceId()).isEqualTo(TRACE_ID);
            assertThat(trace.spanCount()).isEqualTo(5);
            assertThat(trace.services()).containsExactlyInAnyOrder("settlement-engine-backend", "fraud-detection-ml");
            assertThat(trace.durationMicros()).isEqualTo(50000);
            var byOp = trace.spans().stream().collect(java.util.stream.Collectors.toMap(LabJaegerService.SpanView::operation, s -> s));
            assertThat(byOp.get("POST /settlements").offsetMicros()).isZero();
            assertThat(byOp.get("INSERT settlements").kind()).isEqualTo("sql");
            assertThat(byOp.get("INSERT settlements").offsetMicros()).isEqualTo(5000);
            assertThat(byOp.get("settlement.requested publish").kind()).isEqualTo("kafka");
            assertThat(byOp.get("POST").kind()).isEqualTo("http-client");
            assertThat(byOp.get("POST /score").service()).isEqualTo("fraud-detection-ml");
            assertThat(byOp.get("POST /score").error()).isTrue();
            assertThat(byOp.get("POST /score").parentSpanId()).isEqualTo("a4");
            assertThat(byOp.get("INSERT settlements").attributes()).containsEntry("db.system", "postgresql");
        }
    }

    @Test
    void aTraceIdOutsideHexIsRefusedAndNeverReachesTheUpstream() throws Exception {
        try (StubUpstream stub = new StubUpstream()) {
            LabJaegerService svc = service(stub, new LabLogBuffer());
            for (String bad : new String[] {"../admin", "xyz", "4bf92f3577b34da6a3ce929d0e0e47361", "", "abc?x=1", "ABC DEF"}) {
                assertThatThrownBy(() -> svc.trace(bad)).as(bad).isInstanceOf(LabValidationException.class);
            }
            assertThat(stub.requests).isEmpty();
        }
    }

    @Test
    void searchingByRequestIdFindsTraceIdsFromLogLinesAndOpensTheMatchingTrace() throws Exception {
        LabLogBuffer logs = new LabLogBuffer();
        logs.append(new LabLogEvent(0, Instant.now(), "backend", "INFO", "x", "created", "req-1", "s-1", null, TRACE_ID));
        logs.append(new LabLogEvent(0, Instant.now(), "backend", "INFO", "x", "other", "req-2", null, null, "ffffffffffffffffffffffffffffffff"));
        try (StubUpstream stub = new StubUpstream().on("/api/traces/" + TRACE_ID, TRACE_JSON)) {
            List<LabJaegerService.TraceSummary> found = service(stub, logs).search("req-1", null, null, 10);
            assertThat(found).extracting(LabJaegerService.TraceSummary::traceId).containsExactly(TRACE_ID);
            assertThat(found.get(0).spanCount()).isEqualTo(5);
            assertThat(stub.requests).containsExactly("GET /api/traces/" + TRACE_ID);
        }
    }

    @Test
    void searchValidatesItsInputsAndCapsTheLimit() throws Exception {
        try (StubUpstream stub = new StubUpstream().on("/api/traces", "{\"data\":[]}")) {
            LabJaegerService svc = service(stub, new LabLogBuffer());
            assertThatThrownBy(() -> svc.search("x&y=z", null, null, 10)).isInstanceOf(LabValidationException.class);
            assertThatThrownBy(() -> svc.search(null, "not-a-uuid", null, 10)).isInstanceOf(LabValidationException.class);
            assertThatThrownBy(() -> svc.search(null, null, "not-a-uuid", 10)).isInstanceOf(LabValidationException.class);
            svc.search(null, null, null, 100000);
            assertThat(stub.requests).hasSize(1);
            assertThat(stub.requests.get(0)).contains("service=settlement-engine-backend").contains("limit=20");
        }
    }

    @Test
    void anUnreachableJaegerIsReportedAsUnavailable() {
        LabJaegerService svc = new LabJaegerService(LabPropertiesFixtures.defaults().withJaeger("http://127.0.0.1:1"), new LabLogBuffer());
        assertThatThrownBy(() -> svc.trace(TRACE_ID)).isInstanceOf(LabUpstreamUnavailableException.class);
    }
}
