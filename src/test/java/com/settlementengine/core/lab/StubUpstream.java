package com.settlementengine.core.lab;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** A throwaway HTTP server standing in for Jaeger / Prometheus / Alertmanager / ml-service in unit-level tests. */
final class StubUpstream implements AutoCloseable {

    final HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final Map<String, String> bodies = new ConcurrentHashMap<>();
    final Map<String, Integer> statuses = new ConcurrentHashMap<>();

    StubUpstream() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            requests.add(exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (body.length > 0) {
                requests.add("BODY " + new String(body));
            }
            String payload = bodies.getOrDefault(path, "{}");
            int status = statuses.getOrDefault(path, 200);
            byte[] out = payload.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    StubUpstream on(String path, String json) {
        bodies.put(path, json);
        return this;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
