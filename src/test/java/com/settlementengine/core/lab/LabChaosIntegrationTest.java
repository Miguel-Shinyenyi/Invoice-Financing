package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Faults at the boundaries produce the documented end states. Short grace periods so "after the grace
 * period" is a second, not twenty.
 */
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
        "settlement-engine.reconciliation.grace-period-seconds=1",
        "settlement-engine.reconciliation.stale-pending-grace-period-seconds=1"
})
class LabChaosIntegrationTest extends AbstractLabIntegrationTest {

    static final String POOL1 = "10000000-0000-0000-0000-000000000001";
    static final String POOL2 = "10000000-0000-0000-0000-000000000002";
    static final String ALICE = "40000000-0000-0000-0000-000000000001";
    static final String BOB_OK = "50000000-0000-0000-0000-000000000001";
    static final String BOB_DRIFTED = "50000000-0000-0000-0000-000000000002";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    LabResetService reset;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void freshSeed() {
        reset.reset();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private String token(String role) throws Exception {
        MvcResult r = mvc.perform(post("/lab/personas/" + role + "/token")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return json(r).get("token").asText();
    }

    private MvcResult withToken(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String token)
            throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + token)).andReturn();
    }

    private int openMismatchesFor(String ref, String detailsLike) {
        return jdbc.queryForObject("""
                select count(*) from reconciliation_mismatches m join settlements s on s.id = m.settlement_id
                where s.external_ref = ? and m.resolution_status = 'OPEN' and m.details like ?""",
                Integer.class, ref, detailsLike);
    }

    @Test
    void anOrphanedPendingSettlementIsFinalizedAsUnknownByTheSweep() throws Exception {
        MvcResult created = mvc.perform(post("/lab/settlements/orphan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceAccountId\":\"" + POOL1 + "\",\"destinationAccountId\":\"" + POOL2
                        + "\",\"amount\":3.00,\"currency\":\"USD\"}")).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        String id = json(created).get("settlementId").asText();
        assertThat(jdbc.queryForObject("select status from settlements where id = ?::uuid", String.class, id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select k.status from idempotency_keys k join settlements s on s.idempotency_key = k.key "
                + "where s.id = ?::uuid", String.class, id)).isEqualTo("IN_PROGRESS");

        Thread.sleep(1300);
        assertThat(mvc.perform(post("/lab/sweep/run")).andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbc.queryForObject("select status from settlements where id = ?::uuid", String.class, id)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select external_ref from settlements where id = ?::uuid", String.class, id)).isNull();
        assertThat(jdbc.queryForObject("select k.status from idempotency_keys k join settlements s on s.idempotency_key = k.key "
                + "where s.id = ?::uuid", String.class, id)).isEqualTo("COMPLETED");
    }

    @Test
    void forgettingAnExternalRecordProducesANoRecordAfterGraceMismatch() throws Exception {
        assertThat(mvc.perform(post("/lab/external/MOCK-SEED-2/forget")).andReturn().getResponse().getStatus()).isEqualTo(200);
        mvc.perform(post("/lab/reconciliation/run"));
        assertThat(openMismatchesFor("MOCK-SEED-2", "No external record found%")).isEqualTo(1);
    }

    @Test
    void corruptingTheAmountProducesAnAmountMismatch() throws Exception {
        assertThat(mvc.perform(post("/lab/external/MOCK-SEED-3/corrupt").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":11.50}")).andReturn().getResponse().getStatus()).isEqualTo(200);
        mvc.perform(post("/lab/reconciliation/run"));
        assertThat(openMismatchesFor("MOCK-SEED-3", "External record amount/currency%")).isEqualTo(1);
    }

    @Test
    void corruptingTheCurrencyProducesAnAmountOrCurrencyMismatch() throws Exception {
        mvc.perform(post("/lab/external/MOCK-SEED-1/corrupt").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"EUR\"}"));
        mvc.perform(post("/lab/reconciliation/run"));
        assertThat(openMismatchesFor("MOCK-SEED-1", "External record amount/currency%")).isEqualTo(1);
    }

    @Test
    void corruptingTheStatusProducesAStatusMismatch() throws Exception {
        MvcResult corrupted = mvc.perform(post("/lab/external/MOCK-SEED-4/corrupt").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FAILED\"}")).andReturn();
        assertThat(corrupted.getResponse().getStatus()).as(corrupted.getResponse().getContentAsString()).isEqualTo(200);
        mvc.perform(post("/lab/reconciliation/run"));
        assertThat(openMismatchesFor("MOCK-SEED-4", "Internal status%")).isEqualTo(1);
    }

    @Test
    void forgetAndCorruptRefuseUnknownReferences() throws Exception {
        assertThat(mvc.perform(post("/lab/external/NOPE/forget")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/lab/external/NOPE/corrupt").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1}")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aHandEditedBalanceOpensALedgerMismatchAndReadingItReturns500() throws Exception {
        mvc.perform(post("/lab/accounts/" + POOL1 + "/hand-edit-balance").contentType(MediaType.APPLICATION_JSON)
                .content("{\"delta\":40.00}"));
        MvcResult read = withToken(get("/accounts/" + POOL1), token("ADMIN"));
        assertThat(read.getResponse().getStatus()).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from ledger_mismatches where account_id = ?::uuid "
                + "and resolution_status = 'OPEN'", Integer.class, POOL1)).isEqualTo(1);
    }

    @Test
    void resolvingWithoutFixingTheDataMakesTheNextReadOpenAFreshRow() throws Exception {
        mvc.perform(post("/lab/accounts/" + POOL1 + "/hand-edit-balance").contentType(MediaType.APPLICATION_JSON)
                .content("{\"delta\":40.00}"));
        String admin = token("ADMIN");
        assertThat(withToken(get("/accounts/" + POOL1), admin).getResponse().getStatus()).isEqualTo(500);
        String mismatchId = jdbc.queryForObject("select id::text from ledger_mismatches where account_id = ?::uuid "
                + "and resolution_status = 'OPEN'", String.class, POOL1);

        MvcResult resolved = withToken(post("/reconciliation/ledger-mismatches/" + mismatchId + "/resolve")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"looked at it\"}"), admin);
        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);

        assertThat(withToken(get("/accounts/" + POOL1), admin).getResponse().getStatus()).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from ledger_mismatches where account_id = ?::uuid "
                + "and resolution_status = 'OPEN'", Integer.class, POOL1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ledger_mismatches where account_id = ?::uuid",
                Integer.class, POOL1)).isEqualTo(2);
    }

    @Test
    void repairingTheBalanceClosesTheLoop() throws Exception {
        mvc.perform(post("/lab/accounts/" + POOL1 + "/hand-edit-balance").contentType(MediaType.APPLICATION_JSON)
                .content("{\"delta\":-15.00}"));
        String admin = token("ADMIN");
        assertThat(withToken(get("/accounts/" + POOL1), admin).getResponse().getStatus()).isEqualTo(500);
        assertThat(mvc.perform(post("/lab/accounts/" + POOL1 + "/repair-balance")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(withToken(get("/accounts/" + POOL1), admin).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void handEditRejectsAZeroOrHugeDelta() throws Exception {
        for (String delta : new String[] {"0", "1000000"}) {
            assertThat(mvc.perform(post("/lab/accounts/" + POOL1 + "/hand-edit-balance")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":" + delta + "}"))
                    .andReturn().getResponse().getStatus()).as(delta).isEqualTo(400);
        }
    }

    @Test
    void theReadOnlyPersonaSeesItsOwnAccountsAndIsRefusedOthers() throws Exception {
        String ro = token("READ_ONLY");
        assertThat(withToken(get("/accounts/" + ALICE), ro).getResponse().getStatus()).isEqualTo(200);
        assertThat(withToken(get("/accounts/" + BOB_OK), ro).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void readingSomeoneElsesInconsistentAccountIs500NotForbiddenBecauseTheLedgerCheckRunsFirst() throws Exception {
        assertThat(withToken(get("/accounts/" + BOB_DRIFTED), token("READ_ONLY")).getResponse().getStatus()).isEqualTo(500);
    }

    @Test
    void settlementListIsRowLevelFilteredForTheReadOnlyPersona() throws Exception {
        MvcResult r = withToken(get("/settlements?size=100"), token("READ_ONLY"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode content = json(r).get("content");
        assertThat(content.size()).isBetween(1, 5);
        content.forEach(s -> assertThat(List.of(s.get("sourceAccountId").asText(), s.get("destinationAccountId").asText()))
                .anyMatch(id -> id.startsWith("4000")));
    }

    @Test
    void readOnlyCannotCallReconciliationButSupportCan() throws Exception {
        assertThat(withToken(get("/reconciliation/mismatches"), token("READ_ONLY")).getResponse().getStatus()).isEqualTo(403);
        assertThat(withToken(get("/reconciliation/mismatches"), token("SUPPORT")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void unknownPersonaRoleIs400() throws Exception {
        assertThat(mvc.perform(post("/lab/personas/ROOT/token")).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void manualReconciliationRunReturnsTheRunSummary() throws Exception {
        MvcResult r = mvc.perform(post("/lab/reconciliation/run")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(r).get("recordsChecked").asInt()).isGreaterThan(0);
    }

    @Test
    void anUnknownWithoutARefIsNeverPickedUpByReconciliation() throws Exception {
        // Known gap 1, shown as it is: a stranded UNKNOWN stays UNKNOWN after a run, and no mismatch opens for it.
        String stranded = jdbc.queryForObject("select id::text from settlements where status = 'UNKNOWN' "
                + "and external_ref is null limit 1", String.class);
        mvc.perform(post("/lab/reconciliation/run"));
        assertThat(jdbc.queryForObject("select status from settlements where id = ?::uuid", String.class, stranded)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select count(*) from reconciliation_mismatches where settlement_id = ?::uuid",
                Integer.class, stranded)).isZero();
    }
}
