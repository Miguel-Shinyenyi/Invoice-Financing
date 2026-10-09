package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives the three scenarios from load/settlement-load-test.js with REAL HTTP requests to the app's own
 * port on loopback, so the security filter chain, RequestIdFilter, audit logging and JSON serialization
 * are all in the measured path (as with k6).
 */
class LabLoadDriver {

    static final String RUN_HEADER = "X-Lab-Run";

    private final String baseUrl;
    private final String token;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // JDK's default h2c upgrade attempt mangles bodies against some servers
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    LabLoadDriver(int port, String token, ObjectMapper mapper) {
        this.baseUrl = "http://127.0.0.1:" + port;
        this.token = token;
        this.mapper = mapper;
    }

    /** Blocks until the plan's duration, request budget or cancellation ends the run. */
    void run(LoadPlan plan, UUID runId, UUID burstKey, boolean sendRunHeader, LabLoadStats stats, AtomicBoolean cancelled) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(plan.durationSeconds());
        AtomicInteger issued = new AtomicInteger();
        ExecutorService vus = Executors.newFixedThreadPool(plan.virtualUsers());
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        long baseSeed = plan.seed() == null ? System.nanoTime() : plan.seed();
        for (int vu = 0; vu < plan.virtualUsers(); vu++) {
            Random rng = new Random(baseSeed + vu);
            futures.add(vus.submit(() -> {
                while (System.nanoTime() < deadline && !cancelled.get()) {
                    if (issued.get() >= plan.totalRequests()) {
                        return;
                    }
                    switch (plan.scenario()) {
                        case FRESH_SETTLEMENTS -> {
                            if (issued.incrementAndGet() > plan.totalRequests()) {
                                return;
                            }
                            fresh(plan, runId, sendRunHeader, stats, rng);
                            pause(100);
                        }
                        case DUPLICATE_KEY_BURST -> {
                            if (issued.incrementAndGet() > plan.totalRequests()) {
                                return;
                            }
                            duplicate(plan, runId, burstKey, sendRunHeader, stats);
                        }
                        case INVOICE_FINANCING -> {
                            if (issued.incrementAndGet() > plan.totalRequests()) {
                                return;
                            }
                            invoice(plan, runId, sendRunHeader, stats, issued);
                        }
                    }
                }
            }));
        }
        vus.shutdown();
        for (var f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
                // a VU dying is visible as fewer requests, never as a swallowed result
            }
        }
    }

    private void fresh(LoadPlan plan, UUID runId, boolean header, LabLoadStats stats, Random rng) {
        int a = rng.nextInt(LabLoadScope.POOL.size());
        int b = rng.nextInt(LabLoadScope.POOL.size());
        while (b == a) {
            b = rng.nextInt(LabLoadScope.POOL.size());
        }
        String body = "{\"sourceAccountId\":\"" + LabLoadScope.POOL.get(a) + "\",\"destinationAccountId\":\""
                + LabLoadScope.POOL.get(b) + "\",\"amount\":" + plan.amount().toPlainString() + ",\"currency\":\"USD\"}";
        post("/settlements", body, UUID.randomUUID(), runId, header, stats, true);
    }

    private void duplicate(LoadPlan plan, UUID runId, UUID key, boolean header, LabLoadStats stats) {
        String body = "{\"sourceAccountId\":\"" + LabLoadScope.DUP_SOURCE + "\",\"destinationAccountId\":\""
                + LabLoadScope.DUP_DESTINATION + "\",\"amount\":" + plan.amount().toPlainString() + ",\"currency\":\"USD\"}";
        post("/settlements", body, key, runId, header, stats, true);
    }

    private void invoice(LoadPlan plan, UUID runId, boolean header, LabLoadStats stats, AtomicInteger issued) {
        String body = "{\"businessAccountId\":\"" + LabLoadScope.BUSINESS + "\",\"customerReference\":\"lab-load-"
                + UUID.randomUUID() + "\",\"amount\":" + plan.amount().toPlainString()
                + ",\"currency\":\"USD\",\"dueDate\":\"2027-01-01T00:00:00Z\"}";
        JsonNode created = post("/invoices", body, null, runId, header, stats, false);
        if (created == null || !created.hasNonNull("id")) {
            return;
        }
        if (issued.incrementAndGet() > plan.totalRequests()) {
            return;
        }
        post("/invoices/" + created.get("id").asText() + "/finance", "", UUID.randomUUID(), runId, header, stats, false);
    }

    private JsonNode post(String path, String body, UUID idempotencyKey, UUID runId, boolean header, LabLoadStats stats,
                          boolean settlementBody) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("X-Request-Id", "lab-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            req.header("Idempotency-Key", idempotencyKey.toString());
        }
        if (header) {
            req.header(RUN_HEADER, runId.toString());
        }
        long start = System.nanoTime();
        try {
            HttpResponse<String> res = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
            long elapsed = System.nanoTime() - start;
            JsonNode json = null;
            try {
                json = res.body() == null || res.body().isBlank() ? null : mapper.readTree(res.body());
            } catch (Exception ignored) {
                // non-JSON body: counted by status only
            }
            stats.record(res.statusCode(), outcome(res.statusCode(), json, settlementBody), elapsed);
            return json;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            stats.recordTransportFailure(System.nanoTime() - start);
            return null;
        }
    }

    private static String outcome(int status, JsonNode json, boolean settlementBody) {
        if (settlementBody && status == 201 && json != null && json.hasNonNull("status")) {
            return json.get("status").asText();
        }
        if (status == 409 || status == 422) {
            return Integer.toString(status);
        }
        if (!settlementBody && (status == 200 || status == 201)) {
            return status == 200 ? "FINANCED" : "INVOICE_CREATED";
        }
        return null;
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
