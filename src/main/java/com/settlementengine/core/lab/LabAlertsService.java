package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The alert board: the three real rules (state from the sandbox Prometheus, with the real `for` durations read from the
 * shared rules file beside the sandbox's shortened ones), Alertmanager's active alerts, and one fixed row for the rule
 * that does not exist (Known gap 6).
 */
@LabComponent
public class LabAlertsService {

    static final List<String> REAL_RULES = List.of("BackendDown", "MLServiceDown", "SettlementFailureRateSpike");
    private static final Pattern DURATION = Pattern.compile("(\\d+)([smh])");

    private final LabProperties props;
    private final LabUpstreamFetcher prometheus = new LabUpstreamFetcher(List.of("/api/v1/rules"));
    private final LabUpstreamFetcher alertmanager = new LabUpstreamFetcher(List.of("/api/v2/alerts"));

    public LabAlertsService(LabProperties props) {
        this.props = props;
    }

    public Map<String, Object> board() {
        Map<String, Map<String, Object>> real = realDurationsAndExpressions();
        Map<String, JsonNode> live = new LinkedHashMap<>();
        boolean available = true;
        try {
            for (JsonNode group : prometheus.get(props.upstreams().prometheusUrl(), "/api/v1/rules", Map.of()).path("data").path("groups")) {
                for (JsonNode rule : group.path("rules")) {
                    if ("alerting".equals(rule.path("type").asText())) {
                        live.put(rule.path("name").asText(), rule);
                    }
                }
            }
        } catch (RuntimeException e) {
            available = false;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : REAL_RULES) {
            JsonNode rule = live.get(name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("state", rule == null ? "unknown" : rule.path("state").asText("unknown"));
            row.put("expression", rule != null ? rule.path("query").asText() : real.getOrDefault(name, Map.of()).get("expr"));
            row.put("sandboxForSeconds", rule == null ? null : Long.valueOf(rule.path("duration").asLong()));
            row.put("realForSeconds", real.getOrDefault(name, Map.of()).get("forSeconds"));
            row.put("alerts", rule == null ? List.of() : rule.path("alerts"));
            rows.add(row);
        }
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("name", "Open reconciliation mismatches");
        gap.put("state", "no rule exists");
        gap.put("expression", null);
        gap.put("sandboxForSeconds", null);
        gap.put("realForSeconds", null);
        gap.put("note", "reconciliation.mismatch_found is published through the outbox but has no consumer, and no Prometheus "
                + "rule covers a mismatch. A mismatch is seen only if someone opens the reconciliation screen. Known gap 6.");
        rows.add(gap);

        List<Object> active = new ArrayList<>();
        try {
            JsonNode alerts = alertmanager.get(props.upstreams().alertmanagerUrl(), "/api/v2/alerts", Map.of());
            for (JsonNode a : alerts) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", a.path("labels").path("alertname").asText());
                m.put("severity", a.path("labels").path("severity").asText());
                m.put("summary", a.path("annotations").path("summary").asText());
                m.put("startsAt", a.path("startsAt").asText());
                m.put("state", a.path("status").path("state").asText());
                active.add(m);
            }
        } catch (RuntimeException e) {
            // Alertmanager down: the board still shows rule states
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", available);
        out.put("rules", rows);
        out.put("alertmanager", active);
        out.put("note", "The sandbox evaluates the same expressions as staging with shorter `for` durations so a rule can "
                + "reach firing within a minute. The real durations are shown beside them.");
        return out;
    }

    /** `for` and expr from the shared rules file, keyed by alert name. Empty if the file is not mounted. */
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> realDurationsAndExpressions() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Path file = Path.of(props.upstreams().alertRulesFile());
        if (!Files.isReadable(file)) {
            return out;
        }
        try {
            Map<String, Object> doc = new Yaml().load(Files.readString(file));
            for (Map<String, Object> group : (List<Map<String, Object>>) doc.get("groups")) {
                for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("expr", rule.get("expr"));
                    m.put("forSeconds", seconds(String.valueOf(rule.get("for"))));
                    out.put((String) rule.get("alert"), m);
                }
            }
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>();
        }
        return out;
    }

    static Long seconds(String duration) {
        Matcher m = DURATION.matcher(duration);
        long total = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "h" -> n * 3600;
                case "m" -> n * 60;
                default -> n;
            };
        }
        return any ? total : null;
    }
}
