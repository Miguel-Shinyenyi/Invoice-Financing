package com.settlementengine.core.lab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves no lab bean exists unless BOTH the demo profile and the enabled property are present. */
class LabGatingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(LabModuleConfig.class);

    @Test
    void defaultProfileLoadsNoLabBeans() {
        runner.run(ctx -> assertThat(ctx.getBeansWithAnnotation(LabComponent.class)).isEmpty());
    }

    @Test
    void stagingStyleProfileLoadsNoLabBeansEvenWithTheProperty() {
        runner.withPropertyValues("spring.profiles.active=prod", "settlement-engine.demo.enabled=true")
                .run(ctx -> assertThat(ctx.getBeansWithAnnotation(LabComponent.class)).isEmpty());
    }

    @Test
    void demoProfileWithoutThePropertyLoadsNoLabBeans() {
        runner.withPropertyValues("spring.profiles.active=demo")
                .run(ctx -> assertThat(ctx.getBeansWithAnnotation(LabComponent.class)).isEmpty());
    }

    @Test
    void demoProfileWithTheFalseValueLoadsNoLabBeans() {
        runner.withPropertyValues("spring.profiles.active=demo", "settlement-engine.demo.enabled=false")
                .run(ctx -> assertThat(ctx.getBeansWithAnnotation(LabComponent.class)).isEmpty());
    }

    @Test
    void bothTogetherLoadTheLabBeans() {
        runner.withPropertyValues("spring.profiles.active=demo", "settlement-engine.demo.enabled=true")
                .run(ctx -> assertThat(ctx.getBeansWithAnnotation(LabComponent.class)).isNotEmpty());
    }

    @Import(ProbeLabBean.class)
    @org.springframework.context.annotation.Configuration
    static class LabModuleConfig {
    }

    @LabComponent
    static class ProbeLabBean {
    }
}
