package com.settlementengine.core.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlementengine.core.AbstractIntegrationTest;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.repository.LedgerAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SettlementApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private LedgerAccount createAccount(String balance) {
        return ledgerAccountRepository.save(
                new LedgerAccount(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(balance), "USD"));
    }

    private String settlementRequestJson(UUID sourceId, UUID destinationId, String amount) throws Exception {
        return objectMapper.writeValueAsString(new CreateSettlementRequest(sourceId, destinationId, new BigDecimal(amount), "USD"));
    }

    @Test
    void createSettlementReturnsCreatedAndIsRetrievableById() throws Exception {
        LedgerAccount source = createAccount("500.00");
        LedgerAccount destination = createAccount("0.00");
        UUID idempotencyKey = UUID.randomUUID();

        String body = mockMvc.perform(post("/settlements")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementRequestJson(source.getId(), destination.getId(), "100.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn().getResponse().getContentAsString();

        UUID settlementId = objectMapper.readTree(body).get("settlementId").asText().transform(UUID::fromString);

        mockMvc.perform(get("/settlements/{id}", settlementId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void duplicateSubmissionReturnsSameSettlementId() throws Exception {
        LedgerAccount source = createAccount("500.00");
        LedgerAccount destination = createAccount("0.00");
        UUID idempotencyKey = UUID.randomUUID();
        String requestBody = settlementRequestJson(source.getId(), destination.getId(), "50.00");

        String firstResponse = mockMvc.perform(post("/settlements")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstSettlementId = objectMapper.readTree(firstResponse).get("settlementId").asText();

        String secondResponse = mockMvc.perform(post("/settlements")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String secondSettlementId = objectMapper.readTree(secondResponse).get("settlementId").asText();

        org.assertj.core.api.Assertions.assertThat(secondSettlementId).isEqualTo(firstSettlementId);
    }

    @Test
    void missingIdempotencyKeyHeaderReturnsBadRequest() throws Exception {
        LedgerAccount source = createAccount("500.00");
        LedgerAccount destination = createAccount("0.00");

        mockMvc.perform(post("/settlements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementRequestJson(source.getId(), destination.getId(), "10.00")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void insufficientBalanceReturnsUnprocessableEntity() throws Exception {
        LedgerAccount source = createAccount("10.00");
        LedgerAccount destination = createAccount("0.00");

        mockMvc.perform(post("/settlements")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementRequestJson(source.getId(), destination.getId(), "500.00")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getAccountReturnsBalance() throws Exception {
        LedgerAccount account = createAccount("250.50");

        mockMvc.perform(get("/accounts/{id}", account.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(250.50));
    }

    @Test
    void getUnknownAccountReturnsNotFound() throws Exception {
        mockMvc.perform(get("/accounts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
