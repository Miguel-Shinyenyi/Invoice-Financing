package com.settlementengine.core.lab;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every outbound call uses a whitelisted path; visitor values can only ever be URL-encoded query values. */
class LabUpstreamFetcherTest {

    private HttpServer server;
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requested.add(exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()));
            byte[] body = "{\"ok\":true}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private LabUpstreamFetcher fetcher() {
        return new LabUpstreamFetcher(List.of("/api/traces", "/api/traces/{id}", "/api/v1/rules"));
    }

    @Test
    void aWhitelistedPathIsFetchedWithEncodedQueryValues() throws Exception {
        fetcher().get(base, "/api/traces", Map.of("service", "a&b=c", "limit", "5"));
        assertThat(requested).hasSize(1);
        assertThat(requested.get(0)).startsWith("/api/traces?").contains("service=a%26b%3Dc").contains("limit=5");
    }

    @Test
    void aPathOutsideTheWhitelistIsRefusedBeforeAnyRequestIsMade() {
        for (String path : new String[] {"/api/admin", "/", "/api/traces/../../admin", "//evil", "/api/v1/query", "/-/quit",
                "/api/traces?x=1", "/api/traces#frag", "/api/traces\r\nHost: evil"}) {
            assertThatThrownBy(() -> fetcher().get(base, path, Map.of())).as(path).isInstanceOf(LabValidationException.class);
        }
        assertThat(requested).isEmpty();
    }

    @Test
    void aTemplatePathAcceptsOnlySafeSegments() throws Exception {
        fetcher().getTemplate(base, "/api/traces/{id}", "0123456789abcdef", Map.of());
        assertThat(requested.get(0)).isEqualTo("/api/traces/0123456789abcdef");
        for (String hostile : new String[] {"../admin", "a/b", "a?b", "a%2Fb", "", "x y", "a\nb", ".."}) {
            assertThatThrownBy(() -> fetcher().getTemplate(base, "/api/traces/{id}", hostile, Map.of()))
                    .as(hostile).isInstanceOf(LabValidationException.class);
        }
    }

    @Test
    void theBaseUrlComesFromConfigurationOnly() {
        assertThatThrownBy(() -> fetcher().get("file:///etc/passwd", "/api/traces", Map.of()))
                .isInstanceOf(LabValidationException.class);
        assertThatThrownBy(() -> fetcher().get("ftp://x", "/api/traces", Map.of())).isInstanceOf(LabValidationException.class);
    }

    @Test
    void anUnreachableUpstreamIsReportedNotThrownAsAStackTrace() {
        assertThatThrownBy(() -> fetcher().get("http://127.0.0.1:1", "/api/traces", Map.of()))
                .isInstanceOf(LabUpstreamUnavailableException.class);
    }
}
