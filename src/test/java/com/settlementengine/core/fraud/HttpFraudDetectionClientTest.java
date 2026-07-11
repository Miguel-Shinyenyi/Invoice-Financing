package com.settlementengine.core.fraud;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HttpFraudDetectionClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private FraudCheckRequest sampleRequest() {
        return new FraudCheckRequest(UUID.randomUUID(), UUID.randomUUID(), "customer-123",
                new BigDecimal("1000.00"), "USD", new BigDecimal("800.00"), 365, 0, 0);
    }

    private HttpFraudDetectionClient clientPointingAt(int port, long timeoutMs) {
        return new HttpFraudDetectionClient("http://localhost:" + port, timeoutMs);
    }

    @Test
    void parsesSuccessfulResponse() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/score", exchange -> {
            String body = "{\"score\":0.7,\"decision\":\"BLOCK\",\"reasons\":[\"duplicate_customer_reference\",\"new_account_high_advance\"]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        HttpFraudDetectionClient client = clientPointingAt(server.getAddress().getPort(), 2000);
        FraudCheckResult result = client.check(sampleRequest());

        assertThat(result.score()).isEqualByComparingTo("0.7");
        assertThat(result.decision()).isEqualTo(com.settlementengine.core.invoicing.FraudDecision.BLOCK);
        assertThat(result.reasons()).containsExactly("duplicate_customer_reference", "new_account_high_advance");
    }

    @Test
    void failsOpenWhenServiceUnreachable() {
        HttpFraudDetectionClient client = clientPointingAt(1, 2000);

        FraudCheckResult result = client.check(sampleRequest());

        assertThat(result.score()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.decision()).isEqualTo(com.settlementengine.core.invoicing.FraudDecision.ALLOW);
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void failsOpenWhenServiceTimesOut() throws IOException, InterruptedException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/score", exchange -> {
            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{\"score\":0.0,\"decision\":\"ALLOW\",\"reasons\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        HttpFraudDetectionClient client = clientPointingAt(server.getAddress().getPort(), 100);
        FraudCheckResult result = client.check(sampleRequest());

        assertThat(result.decision()).isEqualTo(com.settlementengine.core.invoicing.FraudDecision.ALLOW);
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void failsOpenOnServerError() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/score", exchange -> {
            byte[] bytes = "internal error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        HttpFraudDetectionClient client = clientPointingAt(server.getAddress().getPort(), 2000);
        FraudCheckResult result = client.check(sampleRequest());

        assertThat(result.decision()).isEqualTo(com.settlementengine.core.invoicing.FraudDecision.ALLOW);
        assertThat(result.reasons()).isEmpty();
    }
}
