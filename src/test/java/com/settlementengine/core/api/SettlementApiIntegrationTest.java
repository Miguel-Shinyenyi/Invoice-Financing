package com.settlementengine.core.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlementengine.core.AbstractIntegrationTest;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
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
    @Autowired
    private JwtService jwtService;

    private LedgerAccount createAccount(String balance, UUID ownerId) {
        return ledgerAccountRepository.save(
                new LedgerAccount(UUID.randomUUID(), ownerId, new BigDecimal(balance), "USD"));
    }

    private String settlementRequestJson(UUID sourceId, UUID destinationId, String amount) throws Exception {
        return objectMapper.writeValueAsString(new CreateSettlementRequest(sourceId, destinationId, new BigDecimal(amount), "USD"));
    }

    private String bearer(String role, UUID ownerId) {
        return "Bearer " + jwtService.issueAccessToken(UUID.randomUUID(), role, ownerId);
    }

    private String adminToken() {
        return bearer("ADMIN", null);
    }

    @Test
    void createSettlementReturnsCreatedAndIsRetrievableById() throws Exception {
        UUID ownerId = UUID.randomUUID();
        LedgerAccount source = createAccount("500.00", ownerId);
        LedgerAccount destination = createAccount("0.00", UUID.randomUUID());
        UUID idempotencyKey = UUID.randomUUID();
        String admin = adminToken();

        String body = mockMvc.perform(post("/settlements")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementRequestJson(source.getId(), destination.getId(), "100.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn().getResponse().getContentAsString();

        UUID settlementId = objectMapper.readTree(body).get("settlementId").asText().transform(UUID::fromString);

        mockMvc.perform(get("/settlements/{id}", settlementId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void duplicateSubmissionReturnsSameSettlementId() throws Exception {
        LedgerAccount source = createAccount("500.00", UUID.randomUUID());
        LedgerAccount destination = createAccount("0.00", UUID.randomUUID());
        UUID idempotencyKey = UUID.randomUUID();
        String requestBody = settlementRequestJson(source.getId(), destination.getId(), "50.00");
        String admin = adminToken();

        String firstResponse = mockMvc.perform(post("/settlements")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstSettlementId = objectMapper.readTree(firstResponse).get("settlementId").asText();

        String secondResponse = mockMvc.perform(post("/settlements")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String secondSettlementId = objectMapper.readTree(secondResponse).get("settlementId").asText();

        org.assertj.core.api.Assertions.assertThat(secondSettlementId).isEqualTo(firstSettlementId);
    }

    @Test
    void missingIdempotencyKeyHeaderReturnsBadRequest() throws Exception {
        LedgerAccount source = createAccount("500.00", UUID.randomUUID());
        LedgerAccount destination = createAccount("0.00", UUID.randomUUID());

        mockMvc.perform(post("/settlements")
                        .header(HttpHeaders.AUTHORIZATION, adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementRequestJson(source.getId(), destination.getId(), "10.00")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void insufficientBalanceReturnsUnprocessableEntity() throws Exception {
        LedgerAccount source = createAccount("10.00", UUID.randomUUID());
        LedgerAccount destination = createAccount("0.00", UUID.randomUUID());

        mockMvc.perform(post("/settlements")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementRequestJson(source.getId(), destination.getId(), "500.00")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getAccountReturnsBalance() throws Exception {
        LedgerAccount account = createAccount("250.50", UUID.randomUUID());

        mockMvc.perform(get("/accounts/{id}", account.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(250.50));
    }

    @Test
    void getUnknownAccountReturnsNotFound() throws Exception {
        mockMvc.perform(get("/accounts/{id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void requestWithoutAccessTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/accounts/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readOnlyUserCannotCreateSettlements() throws Exception {
        LedgerAccount source = createAccount("500.00", UUID.randomUUID());
        LedgerAccount destination = createAccount("0.00", UUID.randomUUID());

        mockMvc.perform(post("/settlements")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer("READ_ONLY", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementRequestJson(source.getId(), destination.getId(), "10.00")))
                .andExpect(status().isForbidden());
    }

    @Test
    void readOnlyUserCanSeeOwnAccountButNotAnothers() throws Exception {
        UUID ownerId = UUID.randomUUID();
        LedgerAccount ownAccount = createAccount("100.00", ownerId);
        LedgerAccount otherAccount = createAccount("100.00", UUID.randomUUID());
        String readOnly = bearer("READ_ONLY", ownerId);

        mockMvc.perform(get("/accounts/{id}", ownAccount.getId())
                        .header(HttpHeaders.AUTHORIZATION, readOnly))
                .andExpect(status().isOk());

        mockMvc.perform(get("/accounts/{id}", otherAccount.getId())
                        .header(HttpHeaders.AUTHORIZATION, readOnly))
                .andExpect(status().isForbidden());
    }
}
