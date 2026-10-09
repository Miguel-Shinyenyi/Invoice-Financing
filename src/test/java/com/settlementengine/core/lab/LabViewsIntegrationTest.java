package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlementengine.core.domain.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class LabViewsIntegrationTest extends AbstractLabIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    LabResetService reset;

    @BeforeEach
    void seed() {
        reset.reset();
    }

    private MvcResult call(String uri) throws Exception {
        return mvc.perform(get(uri)).andReturn();
    }

    private JsonNode json(String uri) throws Exception {
        MvcResult r = call(uri);
        assertThat(r.getResponse().getStatus()).as(uri + " " + r.getResponse().getContentAsString()).isEqualTo(200);
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    // ---- data browser whitelist ----

    @Test
    void everyWhitelistedTableIsBrowsable() throws Exception {
        for (String table : List.of("settlements", "idempotency_keys", "ledger_accounts", "ledger_entries", "outbox_events",
                "settlement_read_model", "invoices", "advances", "fraud_assessments", "reconciliation_runs",
                "reconciliation_mismatches", "ledger_mismatches")) {
            JsonNode page = json("/lab/data/" + table);
            assertThat(page.get("columns").size()).as(table).isGreaterThan(0);
            assertThat(page.get("rows").size()).as(table).isLessThanOrEqualTo(25);
            assertThat(page.get("total").asLong()).as(table).isGreaterThan(0L);
        }
    }

    @Test
    void usersRefreshTokensAndEveryOtherNonWhitelistedTableAreRefused() throws Exception {
        for (String table : List.of("users", "refresh_tokens", "audit_log", "flyway_schema_history", "pg_catalog.pg_user",
                "settlements--", "SETTLEMENTS", "information_schema.tables", "lab_load_runs")) {
            assertThat(call("/lab/data/" + table).getResponse().getStatus()).as(table).isEqualTo(404);
        }
        // an encoded semicolon is refused by the firewall (400) or the whitelist (404), never answered with data
        assertThat(call("/lab/data/settlements%3Bdrop%20table%20settlements").getResponse().getStatus()).isIn(400, 404);
    }

    @Test
    void aPathParameterSuffixCannotSmuggleSqlBecauseOnlyTheWhitelistedNameReachesTheQuery() throws Exception {
        // Tomcat strips ";..." path parameters, so this is simply the whitelisted table. The SQL text is built from the
        // whitelist's own strings, never from the request.
        MvcResult r = call("/lab/data/settlements;drop table settlements");
        if (r.getResponse().getStatus() == 200) {
            assertThat(mapper.readTree(r.getResponse().getContentAsString()).get("table").asText()).isEqualTo("settlements");
        }
        assertThat(call("/lab/data/settlements").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void noHashOrSecretColumnIsEverReturned() throws Exception {
        for (String table : List.of("settlements", "idempotency_keys", "ledger_accounts", "invoices")) {
            JsonNode page = json("/lab/data/" + table);
            page.get("columns").forEach(c -> assertThat(c.asText()).as(table).doesNotContain("hash").doesNotContain("password")
                    .doesNotContain("token"));
        }
    }

    @Test
    void aNonWhitelistedColumnIsRefused() throws Exception {
        assertThat(call("/lab/data/settlements?columns=id,password_hash").getResponse().getStatus()).isEqualTo(400);
        assertThat(call("/lab/data/idempotency_keys?columns=request_hash").getResponse().getStatus()).isEqualTo(400);
        assertThat(call("/lab/data/settlements?columns=id;select 1").getResponse().getStatus()).isEqualTo(400);
        JsonNode ok = json("/lab/data/settlements?columns=id,status");
        assertThat(ok.get("columns")).hasSize(2);
    }

    @Test
    void pagingIsBoundedAndValidated() throws Exception {
        assertThat(call("/lab/data/settlements?page=-1").getResponse().getStatus()).isEqualTo(400);
        assertThat(call("/lab/data/settlements?page=abc").getResponse().getStatus()).isEqualTo(400);
        JsonNode second = json("/lab/data/ledger_entries?page=1");
        assertThat(second.get("page").asInt()).isEqualTo(1);
        assertThat(second.get("pageSize").asInt()).isEqualTo(25);
    }

    // ---- audit ----

    @Test
    void auditRowsShowPersonaNamesNotRawUserIds() throws Exception {
        JsonNode audit = json("/lab/audit");
        assertThat(audit.get("rows").size()).isGreaterThan(0);
        List<String> names = new ArrayList<>();
        audit.get("rows").forEach(r -> names.add(r.get("actor").asText()));
        assertThat(names).contains("lab-support", "lab-admin").noneMatch(n -> n.matches("[0-9a-f-]{36}"));
    }

    @Test
    void auditFiltersByActorActionAndLimit() throws Exception {
        assertThat(json("/lab/audit?actor=lab-support").get("rows")).allSatisfy(r ->
                assertThat(r.get("actor").asText()).isEqualTo("lab-support"));
        assertThat(json("/lab/audit?action=RESOLVE_LEDGER_MISMATCH").get("rows")).allSatisfy(r ->
                assertThat(r.get("action").asText()).isEqualTo("RESOLVE_LEDGER_MISMATCH"));
        assertThat(json("/lab/audit?limit=1").get("rows")).hasSize(1);
        assertThat(json("/lab/audit?limit=100000").get("rows").size()).isLessThanOrEqualTo(200);
        assertThat(call("/lab/audit?since=garbage").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void auditPicksUpARealApiCall() throws Exception {
        String token = mapper.readTree(mvc.perform(post("/lab/personas/READ_ONLY/token")).andReturn().getResponse().getContentAsString())
                .get("token").asText();
        mvc.perform(get("/accounts/40000000-0000-0000-0000-000000000001").header("Authorization", "Bearer " + token));
        JsonNode rows = json("/lab/audit?actor=lab-readonly&action=GET_ACCOUNT").get("rows");
        assertThat(rows.size()).isGreaterThan(0);
        assertThat(rows.get(0).get("outcome").asText()).isEqualTo("SUCCESS");
    }

    // ---- state machines ----

    @Test
    void stateMachinesAreReadFromTheEnumsWithLiveCounts() throws Exception {
        JsonNode sm = json("/lab/state-machines");
        JsonNode settlement = sm.get("settlement");
        List<String> edges = new ArrayList<>();
        settlement.get("transitions").forEach(t -> edges.add(t.get("from").asText() + ">" + t.get("to").asText()));
        List<String> expected = new ArrayList<>();
        for (SettlementStatus from : SettlementStatus.values()) {
            for (SettlementStatus to : SettlementStatus.values()) {
                if (from.canTransitionTo(to)) {
                    expected.add(from + ">" + to);
                }
            }
        }
        assertThat(edges).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(settlement.get("states").size()).isEqualTo(SettlementStatus.values().length);
        settlement.get("states").forEach(s -> assertThat(s.has("count")).isTrue());
        assertThat(sm.get("invoice").get("transitions").size()).isGreaterThan(0);
        assertThat(sm.get("advance").get("transitions").size()).isGreaterThan(0);
    }

    @Test
    void reversedIsMarkedAsHavingNoCodePath() throws Exception {
        for (JsonNode s : json("/lab/state-machines").get("settlement").get("states")) {
            if (s.get("name").asText().equals("REVERSED")) {
                assertThat(s.get("noCodePath").asBoolean()).isTrue();
                assertThat(s.get("note").asText()).contains("Known gap 3");
                assertThat(s.get("count").asInt()).isEqualTo(1);
            }
        }
    }

    @Test
    void writeSideAndReadModelCountsPerStatusAreShownSideBySide() throws Exception {
        JsonNode rm = json("/lab/state-machines").get("readModel");
        assertThat(rm.has("writeSide")).isTrue();
        assertThat(rm.has("readSide")).isTrue();
        assertThat(rm.get("writeSide").get("CONFIRMED").asInt()).isEqualTo(rm.get("readSide").get("CONFIRMED").asInt());
    }

    // ---- reconciliation views ----

    @Test
    void resolvedAndOpenSettlementAndLedgerMismatchesAreListed() throws Exception {
        assertThat(json("/lab/reconciliation/mismatches?status=OPEN")).hasSize(1);
        assertThat(json("/lab/reconciliation/mismatches?status=RESOLVED")).hasSize(2);
        assertThat(json("/lab/reconciliation/ledger-mismatches?status=OPEN")).hasSize(1);
        assertThat(json("/lab/reconciliation/ledger-mismatches?status=RESOLVED")).hasSize(1);
        assertThat(call("/lab/reconciliation/mismatches?status=BOGUS").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void theRunHistoryListsSeededRunsNewestFirst() throws Exception {
        JsonNode runs = json("/lab/reconciliation/runs");
        assertThat(runs.size()).isGreaterThanOrEqualTo(5);
        assertThat(runs.get(0).get("startedAt").asText()).isGreaterThanOrEqualTo(runs.get(1).get("startedAt").asText());
    }

    @Test
    void strandedUnknownsAreListedWithTheKnownGapExplanation() throws Exception {
        JsonNode stranded = json("/lab/reconciliation/stranded");
        assertThat(stranded.get("settlements").size()).isEqualTo(2);
        assertThat(stranded.get("explanation").asText()).contains("Known gap 1");
        stranded.get("settlements").forEach(s -> {
            assertThat(s.get("status").asText()).isEqualTo("UNKNOWN");
            assertThat(s.get("externalRef").isNull()).isTrue();
        });
    }

    @Test
    void unseenMismatchesCountGrowsWhenNobodyLooksAndResetsWhenSomeoneDoes() throws Exception {
        mvc.perform(post("/lab/reconciliation/seen"));
        assertThat(json("/lab/reconciliation/summary").get("unseenMismatches").asInt()).isZero();

        mvc.perform(post("/lab/external/MOCK-SEED-2/forget"));
        Thread.sleep(50);
        mvc.perform(post("/lab/reconciliation/run").contentType(MediaType.APPLICATION_JSON));
        // seed settlements are older than the grace period, so the forgotten record is flagged immediately
        JsonNode summary = json("/lab/reconciliation/summary");
        assertThat(summary.get("unseenMismatches").asInt()).isEqualTo(1);
        assertThat(summary.get("openMismatches").asInt()).isEqualTo(2);
        assertThat(summary.get("note").asText()).contains("Known gap 6");

        mvc.perform(post("/lab/reconciliation/seen"));
        assertThat(json("/lab/reconciliation/summary").get("unseenMismatches").asInt()).isZero();
    }
}
