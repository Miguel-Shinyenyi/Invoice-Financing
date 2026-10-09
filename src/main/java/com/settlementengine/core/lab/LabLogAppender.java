package com.settlementengine.core.lab;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/** Copies backend log events into the {@link LabLogBuffer}. Installed only under the demo profile. */
public class LabLogAppender extends AppenderBase<ILoggingEvent> {

    private final LabLogBuffer buffer;

    private LabLogAppender(LabLogBuffer buffer) {
        this.buffer = buffer;
    }

    public static LabLogAppender install(LabLogBuffer buffer) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        LabLogAppender appender = new LabLogAppender(buffer);
        appender.setContext(context);
        appender.setName("LAB_LOG_BUFFER");
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        return appender;
    }

    public void uninstall() {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(this);
        stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        // trace_id is what the OpenTelemetry Java agent puts in the logging MDC.
        buffer.append(new LabLogEvent(0, Instant.ofEpochMilli(event.getTimeStamp()), "backend",
                event.getLevel().toString(), event.getLoggerName(), event.getFormattedMessage(),
                mdc.get("requestId"), mdc.get("settlementId"), mdc.get("invoiceId"), mdc.get("trace_id")));
    }
}
