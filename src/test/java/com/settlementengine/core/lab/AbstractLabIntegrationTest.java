package com.settlementengine.core.lab;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Lab integration tests share ONE Postgres and ONE Kafka for the whole JVM (started once, not per
 * class) so Spring's context cache can be reused across lab test classes. The database is named
 * {@code settlement_engine_lab}: the demo profile's startup guard refuses anything else.
 */
@SpringBootTest(properties = {
        "settlement-engine.demo.enabled=true",
        "settlement-engine.jwt.secret=lab-test-only-secret-0123456789abcdef0123456789abcdef"
})
@ActiveProfiles({"test", "demo"})
public abstract class AbstractLabIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("settlement_engine_lab");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
