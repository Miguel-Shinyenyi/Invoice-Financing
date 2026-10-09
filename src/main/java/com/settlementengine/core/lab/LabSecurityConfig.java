package com.settlementengine.core.lab;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;

/**
 * {@code /lab/**} is public by decision (no login). A separate, earlier filter chain keeps that
 * decision out of the real {@code SecurityConfig}: without the lab gate this chain does not exist
 * and {@code /lab/**} falls through to the real chain's {@code anyRequest().authenticated()}.
 * The real endpoints ({@code /accounts}, {@code /settlements}, ...) stay behind their JWT checks.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
@ConditionalOnProperty(name = "settlement-engine.demo.enabled", havingValue = "true")
public class LabSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain labSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/lab/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
