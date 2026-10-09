package com.settlementengine.core.reconciliation;

import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.events.KafkaTopics;
import com.settlementengine.core.gateway.GatewayResult;
import com.settlementengine.core.gateway.SettlementOutcome;
import com.settlementengine.core.outbox.OutboxWriter;
import com.settlementengine.core.repository.ReconciliationMismatchRepository;
import com.settlementengine.core.repository.ReconciliationRunRepository;
import com.settlementengine.core.repository.SettlementRepository;
import com.settlementengine.core.service.SettlementTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReconciliationServiceTest {

    @Mock
    private SettlementRepository settlementRepository;
    @Mock
    private ExternalReconciliationSource externalReconciliationSource;
    @Mock
    private ReconciliationRunRepository reconciliationRunRepository;
    @Mock
    private ReconciliationMismatchRepository reconciliationMismatchRepository;
    @Mock
    private SettlementTransactions settlementTransactions;
    @Mock
    private OutboxWriter outboxWriter;

    private ReconciliationService reconciliationService;

    private static final long GRACE_PERIOD_SECONDS = 300;

    @BeforeEach
    void setUp() {
        reconciliationService = new ReconciliationService(settlementRepository, externalReconciliationSource,
                reconciliationRunRepository, reconciliationMismatchRepository, settlementTransactions, outboxWriter,
                GRACE_PERIOD_SECONDS);
        when(reconciliationMismatchRepository.findBySettlementIdAndResolutionStatus(any(), any()))
                .thenReturn(Optional.empty());
    }

    private Settlement mockSettlement(UUID id, SettlementStatus status, String externalRef, BigDecimal amount,
                                       String currency, Instant updatedAt) {
        Settlement settlement = mock(Settlement.class);
        when(settlement.getId()).thenReturn(id);
        when(settlement.getStatus()).thenReturn(status);
        when(settlement.getExternalRef()).thenReturn(externalRef);
        when(settlement.getAmount()).thenReturn(amount);
        when(settlement.getCurrency()).thenReturn(currency);
        when(settlement.getUpdatedAt()).thenReturn(updatedAt);
        return settlement;
    }

    @Test
    void noCandidatesCompletesRunWithZeroCounts() {
        when(settlementRepository.findByExternalRefIsNotNull()).thenReturn(List.of());

        ReconciliationRun run = reconciliationService.runOnce();

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(run.getRecordsChecked()).isZero();
        assertThat(run.getMismatchesFound()).isZero();
    }

    @Test
    void matchingConfirmedSettlementProducesNoMismatch() {
        UUID id = UUID.randomUUID();
        Settlement settlement = mockSettlement(id, SettlementStatus.CONFIRMED, "ref-1",
                new BigDecimal("10.00"), "USD", Instant.now());
        when(settlementRepository.findByExternalRefIsNotNull()).thenReturn(List.of(settlement));
        when(externalReconciliationSource.findByReference("ref-1")).thenReturn(Optional.of(
                new ExternalRecord("ref-1", new BigDecimal("10.00"), "USD", ExternalStatus.CONFIRMED)));

        ReconciliationRun run = reconciliationService.runOnce();

        assertThat(run.getMismatchesFound()).isZero();
        verify(reconciliationMismatchRepository, never()).save(any());
        verify(settlementTransactions, never()).finalizeSettlement(any(), any());
    }

    @Test
    void matchingFailedSettlementProducesNoMismatch() {
        UUID id = UUID.randomUUID();
        Settlement settlement = mockSettlement(id, SettlementStatus.FAILED, "ref-2",
                new BigDecimal("10.00"), "USD", Instant.now());
        when(settlementRepository.findByExternalRefIsNotNull()).thenReturn(List.of(settlement));
        when(externalReconciliationSource.findByReference("ref-2")).thenReturn(Optional.of(
                new ExternalRecord("ref-2", new BigDecimal("10.00"), "USD", ExternalStatus.FAILED)));

        ReconciliationRun run = reconciliationService.runOnce();

        assertThat(run.getMismatchesFound()).isZero();
        verify(reconciliationMismatchRepository, never()).save(any());
    }

    @Test
    void unknownSettlementAutoResolvesToConfirmedWhenExternalConfirms() {
        UUID id = UUID.randomUUID();
        Settlement settlement = mockSettlement(id, SettlementStatus.UNKNOWN, "ref-3",
                new BigDecimal("10.00"), "USD", Instant.now());
        when(settlementRepository.findByExternalRefIsNotNull()).thenReturn(List.of(settlement));
        when(externalReconciliationSource.findByReference("ref-3")).thenReturn(Optional.of(
                new ExternalRecord("ref-3", new BigDecimal("10.00"), "USD", ExternalStatus.CONFIRMED)));

        ReconciliationRun run = reconciliationService.runOnce();

        verify(settlementTransactions).finalizeSettlement(id, new GatewayResult(SettlementOutcome.CONFIRMED, "ref-3"));
        verify(outboxWriter).write(any(), eq(id), eq(KafkaTopics.RECONCILIATION_RESOLVED), any());
        assertThat(run.getMismatchesFound()).isZero();
        verify(reconciliationMismatchRepository, never()).save(any());
    }

    @Test
    void unknownSettlementAutoResolvesToFailedWhenExternalReportsFailed() {
        UUID id = UUID.randomUUID();
        Settlement settlement = mockSettlement(id, SettlementStatus.UNKNOWN, "ref-4",
                new BigDecimal("10.00"), "USD", Instant.now());
        when(settlementRepository.findByExternalRefIsNotNull()).thenReturn(List.of(settlement));
        when(externalReconciliationSource.findByReference("ref-4")).thenReturn(Optional.of(
                new ExternalRecord("ref-4", new BigDecimal("10.00"), "USD", ExternalStatus.FAILED)));

        reconciliationService.runOnce();

        verify(settlementTransactions).finalizeSettlement(id, new GatewayResult(SettlementOutcome.FAILED, "ref-4"));
    }

    @Test
    void confirmedSettlementMismatchesWhenExternalReportsFailed() {
        UUID id = UUID.randomUUID();
        Settlement settlement = mockSettlement(id, SettlementStatus.CONFIRMED, "ref-5",
                new BigDecimal("10.00"), "USD", Instant.now());
        when(settlementRepository.findByExternalRefIsNotNull()).thenReturn(List.of(settlement));
        when(externalReconciliationSource.findByReference("ref-5")).thenReturn(Optional.of(
                new ExternalRecord("ref-5", new BigDecimal("10.00"), "USD", ExternalStatus.FAILED)));

        ReconciliationRun run = reconciliationService.runOnce();

        assertThat(run.getMismatchesFound()).isEqualTo(1);
        verify(reconciliationMismatchRepository).save(any());
        verify(settlementTransactions, never()).finalizeSettlement(any(), any());
        verify(outboxWriter).write(any(), any(), eq(KafkaTopics.RECONCILIATION_MISMATCH_FOUND), any());
    }

    @Test
    void failedSettlementMismatchesWhenExternalReportsConfirmed() {
        UUID id = UUID.randomUUID();
        Settlement settlement = mockSettlement(id, SettlementStatus.FAILED, "ref-6",
                new BigDecimal("10.00"), "USD", Instant.now());
        when(settlementRepository.findByExternalRefIsNotNull()).thenReturn(List.of(settlement));
        when(externalReconciliationSource.findByReference("ref-6")).thenReturn(Optional.of(
                new ExternalRecord("ref-6", new BigDecimal("10.00"), "USD", ExternalStatus.CONFIRMED)));

        ReconciliationRun run = reconciliationService.runOnce();

        assertThat(run.getMismatchesFound()).isEqualTo(1);
    }

    @Test
    void amountMismatchIsFlaggedEvenWhenStatusesAgree() {
        UUID id = UUID.randomUUID();
        Settlement settlement = mockSettlement(id, SettlementStatus.CONFIRMED, "ref-7",
                new BigDecimal("10.00"), "USD", Instant.now());
        when(settlementRepository.findByExternalRefIsNotNull()).thenReturn(List.of(settlement));
        when(externalReconciliationSource.findByReference("ref-7")).thenReturn(Optional.of(
                new ExternalRecord("ref-7", new BigDecimal("999.00"), "USD", ExternalStatus.CONFIRMED)));

        ReconciliationRun run = reconciliationService.runOnce();

        assertThat(run.getMismatchesFound()).isEqualTo(1);
    }

    @Test
    void noExternalRecordWithinGracePeriodIsNotYetAMismatch() {
        UUID id = UUID.randomUUID();
        Settlement settlement = mockSettlement(id, SettlementStatus.UNKNOWN, "ref-8",
                new BigDecimal("10.00"), "USD", Instant.now());
        when(settlementRepository.findByExternalRefIsNotNull()).thenReturn(List.of(settlement));
        when(externalReconciliationSource.findByReference("ref-8")).thenReturn(Optional.empty());

        ReconciliationRun run = reconciliationService.runOnce();

        assertThat(run.getMismatchesFound()).isZero();
        verify(reconciliationMismatchRepository, never()).save(any());
    }

    @Test
    void noExternalRecordAfterGracePeriodIsAMismatch() {
        UUID id = UUID.randomUUID();
        Instant longAgo = Instant.now().minus(GRACE_PERIOD_SECONDS + 60, ChronoUnit.SECONDS);
        Settlement settlement = mockSettlement(id, SettlementStatus.UNKNOWN, "ref-9",
                new BigDecimal("10.00"), "USD", longAgo);
        when(settlementRepository.findByExternalRefIsNotNull()).thenReturn(List.of(settlement));
        when(externalReconciliationSource.findByReference("ref-9")).thenReturn(Optional.empty());

        ReconciliationRun run = reconciliationService.runOnce();

        assertThat(run.getMismatchesFound()).isEqualTo(1);
        verify(reconciliationMismatchRepository).save(any());
    }

    @Test
    void doesNotDuplicateAnAlreadyOpenMismatchForTheSameSettlement() {
        UUID id = UUID.randomUUID();
        Settlement settlement = mockSettlement(id, SettlementStatus.CONFIRMED, "ref-10",
                new BigDecimal("10.00"), "USD", Instant.now());
        when(settlementRepository.findByExternalRefIsNotNull()).thenReturn(List.of(settlement));
        when(externalReconciliationSource.findByReference("ref-10")).thenReturn(Optional.of(
                new ExternalRecord("ref-10", new BigDecimal("10.00"), "USD", ExternalStatus.FAILED)));
        when(reconciliationMismatchRepository.findBySettlementIdAndResolutionStatus(id, MismatchResolutionStatus.OPEN))
                .thenReturn(Optional.of(mock(ReconciliationMismatch.class)));

        ReconciliationRun run = reconciliationService.runOnce();

        assertThat(run.getMismatchesFound()).isEqualTo(1);
        verify(reconciliationMismatchRepository, never()).save(any());
    }

    @Test
    void recordsCheckedCountsAllCandidates() {
        Settlement a = mockSettlement(UUID.randomUUID(), SettlementStatus.CONFIRMED, "ref-a",
                new BigDecimal("1.00"), "USD", Instant.now());
        Settlement b = mockSettlement(UUID.randomUUID(), SettlementStatus.FAILED, "ref-b",
                new BigDecimal("2.00"), "USD", Instant.now());
        when(settlementRepository.findByExternalRefIsNotNull()).thenReturn(List.of(a, b));
        when(externalReconciliationSource.findByReference("ref-a")).thenReturn(Optional.of(
                new ExternalRecord("ref-a", new BigDecimal("1.00"), "USD", ExternalStatus.CONFIRMED)));
        when(externalReconciliationSource.findByReference("ref-b")).thenReturn(Optional.of(
                new ExternalRecord("ref-b", new BigDecimal("2.00"), "USD", ExternalStatus.FAILED)));

        ReconciliationRun run = reconciliationService.runOnce();

        assertThat(run.getRecordsChecked()).isEqualTo(2);
        assertThat(run.getMismatchesFound()).isZero();
    }
}
