package com.settlementengine.core.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlementengine.core.AbstractIntegrationTest;
import com.settlementengine.core.LedgerFixtures;
import com.settlementengine.core.domain.AuditOutcome;
import com.settlementengine.core.domain.EntryType;
import com.settlementengine.core.domain.IdempotencyKey;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.domain.LedgerEntry;
import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.reconciliation.LedgerMismatch;
import com.settlementengine.core.reconciliation.MismatchResolutionStatus;
import com.settlementengine.core.repository.AuditLogRepository;
import com.settlementengine.core.repository.IdempotencyKeyRepository;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.repository.LedgerEntryRepository;
import com.settlementengine.core.repository.LedgerMismatchRepository;
import com.settlementengine.core.repository.SettlementRepository;
import com.settlementengine.core.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class LedgerConsistencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    private LedgerMismatchRepository ledgerMismatchRepository;
    @Autowired
    private SettlementRepository settlementRepository;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    private String bearer(String role, UUID ownerId) {
        return "Bearer " + jwtService.issueAccessToken(UUID.randomUUID(), role, ownerId);
    }

    private LedgerAccount account(String balance) {
        return LedgerFixtures.saveAccountWithOpeningEntry(ledgerAccountRepository, ledgerEntryRepository,
                UUID.randomUUID(), new BigDecimal(balance), "USD");
    }

    // A CREDIT on the account that no settlement actually applied to its balance. ledger_entries
    // still needs a real settlement row for its FK, so this hangs it off an unrelated FAILED one.
    private void injectDriftCredit(LedgerAccount account, String amount) {
        LedgerAccount otherA = account("0.00");
        LedgerAccount otherB = account("0.00");
        UUID keyId = UUID.randomUUID();
        idempotencyKeyRepository.save(new IdempotencyKey(keyId, "drift-hash"));
        Settlement unrelated = new Settlement(UUID.randomUUID(), keyId, otherA.getId(), otherB.getId(),
                new BigDecimal(amount), "USD");
        unrelated.transitionTo(SettlementStatus.FAILED);
        settlementRepository.save(unrelated);
        ledgerEntryRepository.save(new LedgerEntry(UUID.randomUUID(), unrelated.getId(), account.getId(),
                EntryType.CREDIT, new BigDecimal(amount)));
    }

    private List<LedgerMismatch> openMismatchesFor(UUID accountId) {
        return ledgerMismatchRepository.findByResolutionStatus(MismatchResolutionStatus.OPEN).stream()
                .filter(m -> m.getAccountId().equals(accountId))
                .toList();
    }

    @Test
    void consistentAccountReadsNormallyAndRecordsNoMismatch() throws Exception {
        LedgerAccount account = account("300.00");

        mockMvc.perform(get("/accounts/{id}", account.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(300.00));

        assertThat(openMismatchesFor(account.getId())).isEmpty();
    }

    @Test
    void driftedAccountReturns500AndRecordsOneOpenMismatchAcrossRepeatedReads() throws Exception {
        LedgerAccount account = account("300.00");
        injectDriftCredit(account, "25.00");
        String admin = bearer("ADMIN", null);

        mockMvc.perform(get("/accounts/{id}", account.getId()).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(containsString(account.getId().toString())))
                .andExpect(jsonPath("$.message").value(containsString("300.0000")))
                .andExpect(jsonPath("$.message").value(containsString("325.0000")));

        List<LedgerMismatch> open = openMismatchesFor(account.getId());
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getStoredBalance()).isEqualByComparingTo("300.00");
        assertThat(open.get(0).getComputedBalance()).isEqualByComparingTo("325.00");

        mockMvc.perform(get("/accounts/{id}", account.getId()).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isInternalServerError());

        assertThat(openMismatchesFor(account.getId())).hasSize(1);
    }

    @Test
    void driftIsCheckedBeforeOwnershipSoANonOwnerStillSurfacesIt() throws Exception {
        LedgerAccount account = account("100.00");
        injectDriftCredit(account, "5.00");

        mockMvc.perform(get("/accounts/{id}", account.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer("READ_ONLY", UUID.randomUUID())))
                .andExpect(status().isInternalServerError());

        assertThat(openMismatchesFor(account.getId())).hasSize(1);
    }

    @Test
    void resolvingALedgerMismatchClosesItAuditsItAndLeavesTheBalanceAlone() throws Exception {
        LedgerAccount account = account("300.00");
        injectDriftCredit(account, "25.00");
        UUID actorId = UUID.randomUUID();
        String admin = "Bearer " + jwtService.issueAccessToken(actorId, "ADMIN", null);

        mockMvc.perform(get("/accounts/{id}", account.getId()).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isInternalServerError());
        UUID mismatchId = openMismatchesFor(account.getId()).get(0).getId();

        mockMvc.perform(get("/reconciliation/ledger-mismatches").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(mismatchId.toString())));

        String resolveBody = objectMapper.writeValueAsString(new ResolveMismatchRequest("Drift entry traced, correcting separately"));
        mockMvc.perform(post("/reconciliation/ledger-mismatches/{id}/resolve", mismatchId)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.details").value(containsString("Drift entry traced")));

        LedgerMismatch resolved = ledgerMismatchRepository.findById(mismatchId).orElseThrow();
        assertThat(resolved.getResolutionStatus()).isEqualTo(MismatchResolutionStatus.RESOLVED);
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(ledgerAccountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("300.00");
        assertThat(auditLogRepository.findAll()).anySatisfy(entry -> {
            assertThat(entry.getActorId()).isEqualTo(actorId);
            assertThat(entry.getAction()).isEqualTo("RESOLVE_LEDGER_MISMATCH");
            assertThat(entry.getTargetTable()).isEqualTo("ledger_mismatches");
            assertThat(entry.getTargetId()).isEqualTo(mismatchId);
            assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        });

        mockMvc.perform(get("/reconciliation/ledger-mismatches").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$[*].id", not(hasItem(mismatchId.toString()))));

        mockMvc.perform(post("/reconciliation/ledger-mismatches/{id}/resolve", mismatchId)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody))
                .andExpect(status().isConflict());
    }

    @Test
    void resolvingAnUnknownLedgerMismatchIsNotFound() throws Exception {
        mockMvc.perform(post("/reconciliation/ledger-mismatches/{id}/resolve", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResolveMismatchRequest("n/a"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void readOnlyUserCannotListLedgerMismatches() throws Exception {
        mockMvc.perform(get("/reconciliation/ledger-mismatches")
                        .header(HttpHeaders.AUTHORIZATION, bearer("READ_ONLY", UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }
}
