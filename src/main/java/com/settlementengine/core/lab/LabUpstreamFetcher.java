package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The only way the lab talks to Jaeger, Prometheus, Alertmanager or the ml-service. Visitor input is never passed
 * through: the base URL is configuration, the path must be one of a fixed list of templates, a template's single
 * placeholder accepts only a safe segment, and query values are URL-encoded.
 */
public class LabUpstreamFetcher {

    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern PLAIN_PATH = Pattern.compile("/[A-Za-z0-9/_.{}-]*");

    private final List<String> allowedPaths;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();

    public LabUpstreamFetcher(List<String> allowedPaths) {
        this.allowedPaths = List.copyOf(allowedPaths);
    }

    public JsonNode get(String base, String path, Map<String, String> query) {
        requireWhitelisted(path);
        return fetch(base, path, query);
    }

    public JsonNode getTemplate(String base, String template, String segment, Map<String, String> query) {
        requireWhitelisted(template);
        if (segment == null || !SAFE_SEGMENT.matcher(segment).matches()) {
            throw new LabValidationException("identifier contains characters that are not allowed");
        }
        return fetch(base, template.replace("{id}", segment), query);
    }

    public JsonNode postJson(String base, String path, String jsonBody) {
        requireWhitelisted(path);
        requireHttp(base);
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(trim(base) + path))
                    .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LabUpstreamUnavailableException("upstream answered " + response.statusCode());
            }
            return response.body() == null || response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
        } catch (LabUpstreamUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LabUpstreamUnavailableException("interrupted");
        } catch (Exception e) {
            throw new LabUpstreamUnavailableException("upstream did not answer");
        }
    }

    private static String trim(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static void requireHttp(String base) {
        if (base == null || !(base.startsWith("http://") || base.startsWith("https://"))) {
            throw new LabValidationException("upstream base URL must be http(s)");
        }
    }

    private void requireWhitelisted(String path) {
        if (path == null || !PLAIN_PATH.matcher(path).matches() || path.contains("..") || path.contains("//")
                || !allowedPaths.contains(path)) {
            throw new LabValidationException("path is not on the lab's fixed list");
        }
    }

    private JsonNode fetch(String base, String path, Map<String, String> query) {
        requireHttp(base);
        StringBuilder url = new StringBuilder(trim(base)).append(path);
        if (!query.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : new TreeMap<>(query).entrySet()) {
                if (!first) {
                    url.append('&');
                }
                first = false;
                url.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
        }
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url.toString())).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LabUpstreamUnavailableException("upstream answered " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (LabUpstreamUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LabUpstreamUnavailableException("interrupted");
        } catch (Exception e) {
            throw new LabUpstreamUnavailableException("upstream did not answer");
        }
    }
}
