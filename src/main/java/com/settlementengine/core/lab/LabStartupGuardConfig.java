package com.settlementengine.core.lab;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Runs as a {@link BeanFactoryPostProcessor}, i.e. before the DataSource, Flyway or any other
 * bean is created, so a wrong database is refused before a single migration or truncate can touch
 * it. Keyed on the {@code demo} profile alone (not also on the enabled property): under the demo
 * profile the check is unconditional.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class LabStartupGuardConfig {

    static final List<String> DEFAULT_FORBIDDEN_HOSTS = List.of("107.155.122.29");

    @Bean
    static BeanFactoryPostProcessor labSafetyGuard(Environment env) {
        return beanFactory -> {
            String url = env.getProperty("spring.datasource.url", "");
            List<String> forbidden = env.getProperty("settlement-engine.demo.forbidden-hosts", String[].class) == null
                    ? DEFAULT_FORBIDDEN_HOSTS
                    : List.of(env.getProperty("settlement-engine.demo.forbidden-hosts", String[].class));
            LabSafetyGuard.verify(url, forbidden);
        };
    }
}
