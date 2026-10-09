package com.settlementengine.core.lab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LabStartupGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LabStartupGuardConfig.class)
            .withPropertyValues("spring.profiles.active=demo");

    @Test
    void demoProfileOnANonLabDatabaseFailsStartup() {
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/settlement_engine")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasStackTraceContaining("_lab"));
    }

    @Test
    void demoProfileOnTheStagingHostFailsStartup() {
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://107.155.122.29:5432/x_lab")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasStackTraceContaining("107.155.122.29"));
    }

    @Test
    void demoProfileOnASandboxDatabaseStarts() {
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://db:5432/settlement_engine_lab")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void customForbiddenHostsAreHonoured() {
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://other.example:5432/x_lab",
                        "settlement-engine.demo.forbidden-hosts=other.example")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void withoutTheDemoProfileTheGuardDoesNothing() {
        new ApplicationContextRunner().withUserConfiguration(LabStartupGuardConfig.class)
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/settlement_engine")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
