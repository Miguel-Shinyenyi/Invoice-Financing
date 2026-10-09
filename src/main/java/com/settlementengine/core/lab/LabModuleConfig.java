package com.settlementengine.core.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("demo")
@ConditionalOnProperty(name = "settlement-engine.demo.enabled", havingValue = "true")
@EnableConfigurationProperties(LabProperties.class)
public class LabModuleConfig {
}
