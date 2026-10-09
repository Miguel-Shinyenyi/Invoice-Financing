package com.settlementengine.core.lab;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Status strip data: each dependency with status, latency and the time of its last check. Cached briefly. */
@LabComponent
public class LabHealthService {

    public record Check(String name, String status, long latencyMs, Instant checkedAt, String detail) {
    }

    private static final Duration CACHE = Duration.ofSeconds(3);

    private final LabProperties props;
    private final HealthEndpoint healthEndpoint;
    private final JdbcTemplate jdbc;
    private final String kafkaBootstrap;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();
    private List<Check> cached = List.of();
    private Instant cachedAt = Instant.EPOCH;

    public LabHealthService(LabProperties props, HealthEndpoint healthEndpoint, JdbcTemplate jdbc,
                            @Value("${spring.kafka.bootstrap-servers}") String kafkaBootstrap) {
        this.props = props;
        this.healthEndpoint = healthEndpoint;
        this.jdbc = jdbc;
        this.kafkaBootstrap = kafkaBootstrap;
    }

    public synchronized Map<String, Object> health() {
        if (Duration.between(cachedAt, Instant.now()).compareTo(CACHE) > 0) {
            List<CompletableFuture<Check>> futures = new ArrayList<>();
            futures.add(run("backend", () -> Status.UP.equals(healthEndpoint.health().getStatus())
                    ? null : "actuator health is not UP"));
            futures.add(run("ml-service", () -> http(props.upstreams().mlUrl() + "/health")));
            futures.add(run("postgres", () -> {
                jdbc.queryForObject("select 1", Integer.class);
                return null;
            }));
            futures.add(run("kafka", this::kafka));
            futures.add(run("jaeger", () -> http(props.upstreams().jaegerUrl() + "/api/services")));
            futures.add(run("prometheus", () -> http(props.upstreams().prometheusUrl() + "/-/ready")));
            futures.add(run("alertmanager", () -> http(props.upstreams().alertmanagerUrl() + "/-/ready")));
            List<Check> checks = new ArrayList<>();
            for (CompletableFuture<Check> f : futures) {
                checks.add(f.join());
            }
            cached = checks;
            cachedAt = Instant.now();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", cached);
        out.put("allUp", cached.stream().allMatch(c -> "UP".equals(c.status())));
        return out;
    }

    /** A check returns null when healthy, or a short reason string when not; an exception also means DOWN. */
    private CompletableFuture<Check> run(String name, Supplier<String> probe) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            String problem;
            try {
                problem = probe.get();
            } catch (RuntimeException e) {
                problem = e.getClass().getSimpleName();
            }
            return new Check(name, problem == null ? "UP" : "DOWN", (System.nanoTime() - start) / 1_000_000, Instant.now(),
                    problem);
        }).completeOnTimeout(new Check(name, "DOWN", 3000, Instant.now(), "timed out"), 3, TimeUnit.SECONDS);
    }

    private String http(String url) {
        try {
            HttpResponse<Void> r = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() / 100 == 2 ? null : "answered " + r.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            return "unreachable";
        }
    }

    private String kafka() {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "2000");
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "2500");
        try (AdminClient admin = AdminClient.create(p)) {
            admin.describeCluster().nodes().get(2, TimeUnit.SECONDS);
            return null;
        } catch (TimeoutException e) {
            return "timed out";
        } catch (Exception e) {
            return "unreachable";
        }
    }
}
