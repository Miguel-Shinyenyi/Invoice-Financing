package com.settlementengine.core.lab;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabMlServiceTest {

    static final String ML_LOGS = """
            {"events":[
              {"seq":1,"timestamp":"2026-10-09T12:00:00+00:00","service":"ml-service","level":"INFO","logger":"uvicorn","message":"scored","requestId":"req-9","traceId":null},
              {"seq":2,"timestamp":"2026-10-09T12:00:01+00:00","service":"ml-service","level":"WARNING","logger":"main","message":"slow","requestId":"req-9","traceId":"abc123"}
            ],"capacity":2000,"size":2}
            """;

    @Test
    void mlLogsAreMergedIntoTheBackendBufferWithAServiceFieldAndNotDuplicated() throws Exception {
        try (StubUpstream ml = new StubUpstream().on("/lab/logs", ML_LOGS)) {
            LabLogBuffer buffer = new LabLogBuffer();
            LabMlService svc = new LabMlService(LabPropertiesFixtures.defaults().withMl(ml.url()), buffer, new AtomicLong(0)::get);
            svc.pollLogs();
            svc.pollLogs();
            var merged = buffer.query(new LabLogFilter(null, null, "req-9", null, null, null, "ml-service"), 10);
            assertThat(merged).hasSize(2);
            assertThat(merged.get(1).level()).isEqualTo("WARNING");
            assertThat(merged.get(1).traceId()).isEqualTo("abc123");
            assertThat(merged.get(0).service()).isEqualTo("ml-service");
        }
    }

    @Test
    void aRestartedMlServiceWithLowerSequenceNumbersIsStillMerged() throws Exception {
        try (StubUpstream ml = new StubUpstream().on("/lab/logs", ML_LOGS)) {
            LabLogBuffer buffer = new LabLogBuffer();
            LabMlService svc = new LabMlService(LabPropertiesFixtures.defaults().withMl(ml.url()), buffer, new AtomicLong(0)::get);
            svc.pollLogs();
            ml.on("/lab/logs", "{\"events\":[{\"seq\":1,\"timestamp\":\"2026-10-09T13:00:00+00:00\",\"service\":\"ml-service\","
                    + "\"level\":\"INFO\",\"logger\":\"m\",\"message\":\"after restart\",\"requestId\":null,\"traceId\":null}]}");
            svc.pollLogs();
            assertThat(buffer.query(LabLogFilter.NONE, 10)).extracting(LabLogEvent::message).contains("after restart");
        }
    }

    @Test
    void theAccessLogLinesOfThePollItselfAreNotMergedBack() throws Exception {
        try (StubUpstream ml = new StubUpstream().on("/lab/logs", "{\"events\":["
                + "{\"seq\":1,\"timestamp\":\"2026-10-09T12:00:00+00:00\",\"level\":\"INFO\",\"logger\":\"uvicorn.access\","
                + "\"message\":\"1.2.3.4:5 - GET /lab/logs?limit=200 HTTP/1.1 200\"},"
                + "{\"seq\":2,\"timestamp\":\"2026-10-09T12:00:01+00:00\",\"level\":\"INFO\",\"logger\":\"uvicorn.access\","
                + "\"message\":\"1.2.3.4:5 - POST /score HTTP/1.1 200\"}]}")) {
            LabLogBuffer buffer = new LabLogBuffer();
            new LabMlService(LabPropertiesFixtures.defaults().withMl(ml.url()), buffer, new AtomicLong(0)::get).pollLogs();
            assertThat(buffer.query(LabLogFilter.NONE, 10)).extracting(LabLogEvent::message).hasSize(1).first().asString().contains("POST /score");
        }
    }

    @Test
    void anUnreachableMlServiceDoesNotBreakPolling() {
        LabLogBuffer buffer = new LabLogBuffer();
        new LabMlService(LabPropertiesFixtures.defaults().withMl("http://127.0.0.1:1"), buffer, System::nanoTime).pollLogs();
        assertThat(buffer.size()).isZero();
    }

    @Test
    void theFaultIsForwardedWithinTheSixtySecondCap() throws Exception {
        try (StubUpstream ml = new StubUpstream().on("/lab/fault", "{\"active\":true,\"remainingSeconds\":30}")) {
            LabMlService svc = new LabMlService(LabPropertiesFixtures.defaults().withMl(ml.url()), new LabLogBuffer(),
                    new AtomicLong(0)::get);
            var result = svc.startFault(30);
            assertThat(result).containsEntry("seconds", 30);
            assertThat(ml.requests).contains("POST /lab/fault").anyMatch(r -> r.startsWith("BODY") && r.contains("30"));
        }
    }

    @Test
    void secondsOutsideOneToSixtyAreRefusedBeforeCallingTheMlService() throws Exception {
        try (StubUpstream ml = new StubUpstream()) {
            LabMlService svc = new LabMlService(LabPropertiesFixtures.defaults().withMl(ml.url()), new LabLogBuffer(),
                    new AtomicLong(0)::get);
            for (int bad : new int[] {0, -1, 61, 100000}) {
                assertThatThrownBy(() -> svc.startFault(bad)).as("" + bad).isInstanceOf(LabValidationException.class);
            }
            assertThat(ml.requests).isEmpty();
        }
    }

    @Test
    void aSecondFaultInsideTheCooldownIsRefused() throws Exception {
        try (StubUpstream ml = new StubUpstream().on("/lab/fault", "{\"active\":true}")) {
            AtomicLong now = new AtomicLong(0);
            LabMlService svc = new LabMlService(LabPropertiesFixtures.defaults().withMl(ml.url()), new LabLogBuffer(), now::get);
            svc.startFault(10);
            assertThatThrownBy(() -> svc.startFault(10)).isInstanceOf(LabBusyException.class);
            now.addAndGet(java.time.Duration.ofSeconds(31).toNanos());
            svc.startFault(10);
        }
    }
}
