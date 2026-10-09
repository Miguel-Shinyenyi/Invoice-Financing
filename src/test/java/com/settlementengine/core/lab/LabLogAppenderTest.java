package com.settlementengine.core.lab;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class LabLogAppenderTest {

    private LabLogBuffer buffer;
    private LabLogAppender appender;

    @BeforeEach
    void install() {
        buffer = new LabLogBuffer(50);
        appender = LabLogAppender.install(buffer);
    }

    @AfterEach
    void uninstall() {
        appender.uninstall();
        MDC.clear();
    }

    @Test
    void capturesLevelLoggerMessageAndTheMdcFieldsTheJsonEncoderCarries() {
        Logger log = LoggerFactory.getLogger("com.settlementengine.core.service.SettlementService");
        MDC.put("requestId", "req-1");
        MDC.put("settlementId", "set-1");
        MDC.put("invoiceId", "inv-1");
        MDC.put("trace_id", "abc123");
        log.warn("something {}", "happened");

        LabLogEvent e = buffer.query(new LabLogFilter(null, null, "req-1", null, null, null, null), 5).get(0);
        assertThat(e.service()).isEqualTo("backend");
        assertThat(e.level()).isEqualTo("WARN");
        assertThat(e.logger()).isEqualTo("com.settlementengine.core.service.SettlementService");
        assertThat(e.message()).isEqualTo("something happened");
        assertThat(e.settlementId()).isEqualTo("set-1");
        assertThat(e.invoiceId()).isEqualTo("inv-1");
        assertThat(e.traceId()).isEqualTo("abc123");
        assertThat(e.timestamp()).isNotNull();
    }

    @Test
    void uninstallStopsCapture() {
        appender.uninstall();
        LoggerFactory.getLogger("x").info("after uninstall");
        assertThat(buffer.size()).isZero();
        appender = LabLogAppender.install(buffer);
    }

    @Test
    void loggingNeverBlowsUpWhenTheMdcIsEmpty() {
        LoggerFactory.getLogger("x").error("no mdc", new RuntimeException("boom"));
        assertThat(buffer.query(LabLogFilter.NONE, 5)).extracting(LabLogEvent::level).contains("ERROR");
    }
}
