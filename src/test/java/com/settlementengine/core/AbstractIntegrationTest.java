package com.settlementengine.core;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Every subclass shares this single static container field (that's plain Java: a static field
// belongs to the class that declares it, not to each subclass), and Testcontainers restarts it
// per test class, which reassigns its host port each time. Without @DirtiesContext, Spring's test
// context cache can hand a later test class a cached ApplicationContext whose DataSource still
// points at an earlier, now-dead container port -- found empirically as a
// "Connection to localhost:<old-port> refused" failure when running the full suite.
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
}
