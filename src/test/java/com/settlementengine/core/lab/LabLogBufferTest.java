package com.settlementengine.core.lab;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LabLogBufferTest {

    private static LabLogEvent event(String level, String logger, String msg, String requestId, String settlementId) {
        return new LabLogEvent(0, Instant.now(), "backend", level, logger, msg, requestId, settlementId, null, null);
    }

    @Test
    void ringKeepsOnlyTheLastNEventsAndDropsTheOldest() {
        LabLogBuffer buffer = new LabLogBuffer(5);
        for (int i = 0; i < 12; i++) {
            buffer.append(event("INFO", "a", "msg " + i, null, null));
        }
        List<LabLogEvent> all = buffer.query(LabLogFilter.NONE, 100);
        assertThat(all).hasSize(5);
        assertThat(all.get(0).message()).isEqualTo("msg 7");
        assertThat(all.get(4).message()).isEqualTo("msg 11");
    }

    @Test
    void theDefaultCapacityIs2000() {
        assertThat(new LabLogBuffer().capacity()).isEqualTo(2000);
        LabLogBuffer buffer = new LabLogBuffer();
        for (int i = 0; i < 2500; i++) {
            buffer.append(event("INFO", "a", "m" + i, null, null));
        }
        assertThat(buffer.size()).isEqualTo(2000);
    }

    @Test
    void sequenceNumbersIncreaseSoStreamsCanResume() {
        LabLogBuffer buffer = new LabLogBuffer(10);
        buffer.append(event("INFO", "a", "one", null, null));
        buffer.append(event("INFO", "a", "two", null, null));
        List<LabLogEvent> all = buffer.query(LabLogFilter.NONE, 10);
        assertThat(all.get(1).seq()).isGreaterThan(all.get(0).seq());
        assertThat(buffer.after(all.get(0).seq(), LabLogFilter.NONE, 10)).extracting(LabLogEvent::message).containsExactly("two");
    }

    @Test
    void filtersByMinimumLevel() {
        LabLogBuffer buffer = new LabLogBuffer(10);
        buffer.append(event("DEBUG", "a", "d", null, null));
        buffer.append(event("INFO", "a", "i", null, null));
        buffer.append(event("WARN", "a", "w", null, null));
        buffer.append(event("ERROR", "a", "e", null, null));
        assertThat(buffer.query(new LabLogFilter("WARN", null, null, null, null, null, null), 10))
                .extracting(LabLogEvent::message).containsExactly("w", "e");
    }

    @Test
    void filtersByLoggerPrefixRequestIdSettlementIdTextAndService() {
        LabLogBuffer buffer = new LabLogBuffer(10);
        buffer.append(event("INFO", "com.settlementengine.core.service.SettlementService", "created", "r1", "s1"));
        buffer.append(event("INFO", "org.hibernate.SQL", "select 1", "r2", "s2"));
        buffer.append(new LabLogEvent(0, Instant.now(), "ml-service", "INFO", "uvicorn", "scored invoice", "r1", null, null, null));

        assertThat(buffer.query(new LabLogFilter(null, "com.settlementengine", null, null, null, null, null), 10)).hasSize(1);
        assertThat(buffer.query(new LabLogFilter(null, null, "r1", null, null, null, null), 10)).hasSize(2);
        assertThat(buffer.query(new LabLogFilter(null, null, null, "s2", null, null, null), 10)).hasSize(1);
        assertThat(buffer.query(new LabLogFilter(null, null, null, null, "SCORED", null, null), 10)).hasSize(1);
        assertThat(buffer.query(new LabLogFilter(null, null, null, null, null, null, "ml-service"), 10)).hasSize(1);
    }

    @Test
    void filtersBySinceTimestamp() {
        LabLogBuffer buffer = new LabLogBuffer(10);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        buffer.append(new LabLogEvent(0, t0, "backend", "INFO", "a", "old", null, null, null, null));
        buffer.append(new LabLogEvent(0, t0.plusSeconds(60), "backend", "INFO", "a", "new", null, null, null, null));
        assertThat(buffer.query(new LabLogFilter(null, null, null, null, null, t0.plusSeconds(30), null), 10))
                .extracting(LabLogEvent::message).containsExactly("new");
    }

    @Test
    void queryLimitIsHonouredAndReturnsTheNewestMatches() {
        LabLogBuffer buffer = new LabLogBuffer(100);
        for (int i = 0; i < 50; i++) {
            buffer.append(event("INFO", "a", "m" + i, null, null));
        }
        List<LabLogEvent> out = buffer.query(LabLogFilter.NONE, 3);
        assertThat(out).extracting(LabLogEvent::message).containsExactly("m47", "m48", "m49");
    }

    @Test
    void subscribersReceiveNewEventsUntilTheyUnsubscribe() {
        LabLogBuffer buffer = new LabLogBuffer(10);
        List<String> seen = new ArrayList<>();
        AutoCloseable sub = buffer.subscribe(e -> seen.add(e.message()));
        buffer.append(event("INFO", "a", "one", null, null));
        try {
            sub.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        buffer.append(event("INFO", "a", "two", null, null));
        assertThat(seen).containsExactly("one");
    }

    @Test
    void resetClearsTheBuffer() {
        LabLogBuffer buffer = new LabLogBuffer(10);
        buffer.append(event("INFO", "a", "one", null, null));
        buffer.resetInMemory();
        assertThat(buffer.size()).isZero();
    }
}
