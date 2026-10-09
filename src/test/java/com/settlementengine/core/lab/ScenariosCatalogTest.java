package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The catalog the UI renders must stay true: required fields, real doc anchors, real classes and methods. */
class ScenariosCatalogTest {

    private static final JsonNode CATALOG = load();

    private static JsonNode load() {
        try {
            return new ObjectMapper().readTree(Files.readString(Path.of("lab/seed/scenarios.json")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> anchors(String docPath) throws Exception {
        Set<String> out = new HashSet<>();
        for (String line : Files.readAllLines(Path.of(docPath))) {
            if (line.startsWith("#")) {
                String title = line.replaceFirst("^#+\\s*", "").trim().toLowerCase(Locale.ROOT);
                out.add(title.replaceAll("[^a-z0-9 \\-]", "").replace(' ', '-'));
            }
        }
        return out;
    }

    private static String javaSource(String className) throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            Path file = files.filter(p -> p.getFileName().toString().equals(className + ".java")).findFirst()
                    .orElseThrow(() -> new AssertionError("no class " + className));
            return Files.readString(file);
        }
    }

    @Test
    void everyEntryHasEveryFieldTheUiRenders() {
        assertThat(CATALOG.size()).isGreaterThanOrEqualTo(25);
        Set<String> ids = new HashSet<>();
        for (JsonNode s : CATALOG) {
            for (String f : new String[] {"id", "group", "title", "visitorSees", "lookFor", "honestLimit"}) {
                assertThat(s.path(f).asText()).as(s.path("id").asText() + "." + f).isNotBlank();
            }
            assertThat(s.get("engineDoes").size()).as(s.get("id").asText()).isGreaterThan(0);
            assertThat(s.get("doc").path("path").asText()).isNotBlank();
            assertThat(s.get("action").path("route").asText()).startsWith("/lab");
            assertThat(ids.add(s.get("id").asText())).as("duplicate id " + s.get("id")).isTrue();
        }
    }

    @Test
    void everyDocLinkPointsAtARealHeading() throws Exception {
        for (JsonNode s : CATALOG) {
            JsonNode doc = s.get("doc");
            assertThat(Files.exists(Path.of(doc.get("path").asText()))).as(doc.get("path").asText()).isTrue();
            assertThat(anchors(doc.get("path").asText())).as(s.get("id") + " -> " + doc).contains(doc.get("anchor").asText());
        }
    }

    @Test
    void everyClassAndMethodNamedInEngineDoesExists() throws Exception {
        for (JsonNode s : CATALOG) {
            for (JsonNode e : s.get("engineDoes")) {
                String source = javaSource(e.get("class").asText());
                assertThat(source).as(s.get("id") + ": " + e.get("class") + "." + e.get("method")).contains(e.get("method").asText() + "(");
            }
        }
    }

    @Test
    void everyKnownGapIsOneToSixAndAllSixAreCovered() {
        Set<Integer> gaps = new HashSet<>();
        for (JsonNode s : CATALOG) {
            if (!s.get("knownGap").isNull()) {
                assertThat(s.get("knownGap").asInt()).isBetween(1, 6);
                gaps.add(s.get("knownGap").asInt());
            }
        }
        assertThat(gaps).as("gap 4 is the missing ledger-mismatch screen, closed by the rewrite, so it has no scenario")
                .contains(1, 2, 3, 5, 6);
    }
}
