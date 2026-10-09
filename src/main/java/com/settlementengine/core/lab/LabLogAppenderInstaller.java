package com.settlementengine.core.lab;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@LabComponent
public class LabLogAppenderInstaller {

    private final LabLogBuffer buffer;
    private LabLogAppender appender;

    public LabLogAppenderInstaller(LabLogBuffer buffer) {
        this.buffer = buffer;
    }

    @PostConstruct
    void install() {
        appender = LabLogAppender.install(buffer);
    }

    @PreDestroy
    void uninstall() {
        if (appender != null) {
            appender.uninstall();
        }
    }
}
