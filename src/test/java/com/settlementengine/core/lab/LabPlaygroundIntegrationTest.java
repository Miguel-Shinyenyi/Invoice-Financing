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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class LabPlaygroundIntegrationTest extends AbstractLabIntegrationTest {

    static final String A = "10000000-0000-0000-0000-000000000001";
    static final String B = "10000000-0000-0000-0000-000000000002";

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

    private JsonNode createSettlement(UUID key, String amount, String faultMode) throws Exception {
        String fault = faultMode == null ? "" : ",\"fault\":{\"mode\":\"" + faultMode + "\"}";
        MvcResult res = mvc.perform(post("/lab/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"" + key + "\",\"sourceAccountId\":\"" + A
                                + "\",\"destinationAccountId\":\"" + B + "\",\"amount\":" + amount
                                + ",\"currency\":\"USD\"" + fault + "}"))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsString());
    }

    @Test
    void aCleanSettlementConfirmsAndReportsOrderedStepsFromRealRows() throws Exception {
        JsonNode out = createSettlement(UUID.randomUUID(), "12.50", null);
        assertThat(out.get("httpStatus").asInt()).isEqualTo(201);
        assertThat(out.get("result").get("status").asText()).isEqualTo("CONFIRMED");
        java.util.List<String> keys = new java.util.ArrayList<>();
        out.get("steps").forEach(s -> keys.add(s.get("key").asText()));
        assertThat(keys).containsSubsequence("IDEMPOTENCY_KEY_WRITTEN", "PENDING_ROW", "GATEWAY_CALL", "FINALIZE",
                "LEDGER_ENTRIES", "OUTBOX_EVENTS");
    }

    @Test
    void requestLostEndsUnknownWithNoReference() throws Exception {
        JsonNode out = createSettlement(UUID.randomUUID(), "5.00", "REQUEST_LOST");
        assertThat(out.get("result").get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(out.get("result").get("externalRef").isNull()).isTrue();
        assertThat(out.get("stranded").asBoolean()).isTrue();
        assertThat(out.get("externalRecordHeld").asBoolean()).isFalse();
    }

    @Test
    void responseLostEndsUnknownWithNoRefButTheExternalSystemHoldsARecord() throws Exception {
        JsonNode out = createSettlement(UUID.randomUUID(), "5.00", "RESPONSE_LOST");
        assertThat(out.get("result").get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(out.get("result").get("externalRef").isNull()).isTrue();
        assertThat(out.get("stranded").asBoolean()).isTrue();
        assertThat(out.get("externalRecordHeld").asBoolean()).isTrue();
        assertThat(out.get("orphanedExternalRef").asText()).startsWith("MOCK-");
    }

    @Test
    void declinedEndsFailedAndMovesNoMoney() throws Exception {
        java.math.BigDecimal before = jdbc.queryForObject("select balance from ledger_accounts where id = ?::uuid",
                java.math.BigDecimal.class, A);
        JsonNode out = createSettlement(UUID.randomUUID(), "5.00", "DECLINED");
        assertThat(out.get("result").get("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select balance from ledger_accounts where id = ?::uuid",
                java.math.BigDecimal.class, A)).isEqualByComparingTo(before);
    }

    @Test
    void slowStillConfirms() throws Exception {
        JsonNode out = createSettlement(UUID.randomUUID(), "5.00", "SLOW");
        assertThat(out.get("result").get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void retryingTheSameKeyAndBodyReturnsTheCachedResultAndMovesMoneyOnce() throws Exception {
        UUID key = UUID.randomUUID();
        java.math.BigDecimal before = jdbc.queryForObject("select balance from ledger_accounts where id = ?::uuid",
                java.math.BigDecimal.class, A);
        JsonNode first = createSettlement(key, "7.00", null);
        JsonNode second = createSettlement(key, "7.00", null);
        assertThat(second.get("result").get("settlementId").asText()).isEqualTo(first.get("result").get("settlementId").asText());
        assertThat(second.get("replay").asBoolean()).isTrue();
        assertThat(first.get("replay").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select balance from ledger_accounts where id = ?::uuid",
                java.math.BigDecimal.class, A)).isEqualByComparingTo(before.subtract(new java.math.BigDecimal("7.00")));
    }

    @Test
    void reusingAKeyWithADifferentBodyIsA409() throws Exception {
        UUID key = UUID.randomUUID();
        createSettlement(key, "7.00", null);
        JsonNode second = createSettlement(key, "8.00", null);
        assertThat(second.get("httpStatus").asInt()).isEqualTo(409);
        assertThat(second.get("error").asText()).isNotBlank();
    }

    @Test
    void anOutOfRangeAmountIsRefusedWith400() throws Exception {
        mvc.perform(post("/lab/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"sourceAccountId\":\"" + A
                                + "\",\"destinationAccountId\":\"" + B + "\",\"amount\":100000,\"currency\":\"USD\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }

    @Test
    void theInspectorReturnsEveryRelatedRowReadOnly() throws Exception {
        JsonNode out = createSettlement(UUID.randomUUID(), "9.00", null);
        String id = out.get("result").get("settlementId").asText();
        MvcResult res = mvc.perform(get("/lab/settlements/" + id + "/inspect")).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode inspect = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(inspect.get("settlement").get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(inspect.get("idempotencyKey").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(inspect.get("ledgerEntries")).hasSize(2);
        assertThat(inspect.get("outboxEvents").size()).isGreaterThanOrEqualTo(2);
        assertThat(inspect.get("logs").size()).isGreaterThan(0);
        assertThat(inspect.has("mismatches")).isTrue();
        assertThat(inspect.has("audit")).isTrue();
    }

    @Test
    void inspectingAnUnknownSettlementIs404() throws Exception {
        mvc.perform(get("/lab/settlements/" + UUID.randomUUID() + "/inspect"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

    @Test
    void errorsNeverCarryAStackTrace() throws Exception {
        MvcResult res = mvc.perform(post("/lab/settlements").contentType(MediaType.APPLICATION_JSON).content("{bad json"))
                .andReturn();
        assertThat(res.getResponse().getContentAsString()).doesNotContain("at com.", "Exception", "trace");
    }

    @Test
    void labEndpointsArePublicWithNoToken() throws Exception {
        mvc.perform(get("/lab/status")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
}
