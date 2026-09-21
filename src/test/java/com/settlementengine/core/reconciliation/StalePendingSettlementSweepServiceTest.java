package com.settlementengine.core.reconciliation;

import com.settlementengine.core.domain.IllegalStateTransitionException;
import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.gateway.GatewayResult;
import com.settlementengine.core.gateway.SettlementOutcome;
import com.settlementengine.core.repository.SettlementRepository;
import com.settlementengine.core.service.SettlementTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StalePendingSettlementSweepServiceTest {

    @Mock
    private SettlementRepository settlementRepository;
    @Mock
    private SettlementTransactions settlementTransactions;

    private static final long GRACE_PERIOD_SECONDS = 300;

    private StalePendingSettlementSweepService sweepService;

    @BeforeEach
    void setUp() {
        sweepService = new StalePendingSettlementSweepService(
                settlementRepository, settlementTransactions, GRACE_PERIOD_SECONDS);
    }

    @Test
    void staleOrphanedPendingSettlementIsFinalizedAsUnknown() {
        UUID id = UUID.randomUUID();
        Settlement settlement = mock(Settlement.class);
        when(settlement.getId()).thenReturn(id);
        when(settlementRepository.findByStatusAndExternalRefIsNullAndUpdatedAtBefore(eq(SettlementStatus.PENDING), any()))
                .thenReturn(List.of(settlement));

        sweepService.runOnce();

        verify(settlementTransactions).finalizeSettlement(id, new GatewayResult(SettlementOutcome.UNKNOWN, null));
    }

    @Test
    void queriesUsingTheConfiguredGracePeriodAsTheCutoff() {
        when(settlementRepository.findByStatusAndExternalRefIsNullAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of());
        Instant before = Instant.now().minus(GRACE_PERIOD_SECONDS, ChronoUnit.SECONDS);

        sweepService.runOnce();

        verify(settlementRepository).findByStatusAndExternalRefIsNullAndUpdatedAtBefore(
                eq(SettlementStatus.PENDING),
                org.mockito.ArgumentMatchers.argThat(cutoff ->
                        !cutoff.isBefore(before.minusSeconds(2)) && !cutoff.isAfter(before.plusSeconds(2))));
    }

    @Test
    void settlementAlreadyResolvedByAnotherProcessIsSkippedWithoutError() {
        UUID id = UUID.randomUUID();
        Settlement settlement = mock(Settlement.class);
        when(settlement.getId()).thenReturn(id);
        when(settlementRepository.findByStatusAndExternalRefIsNullAndUpdatedAtBefore(eq(SettlementStatus.PENDING), any()))
                .thenReturn(List.of(settlement));
        when(settlementTransactions.finalizeSettlement(eq(id), any()))
                .thenThrow(new IllegalStateTransitionException(SettlementStatus.UNKNOWN, SettlementStatus.UNKNOWN));

        sweepService.runOnce();

        verify(settlementTransactions).finalizeSettlement(eq(id), any());
    }

    @Test
    void oneSettlementFailingToFinalizeDoesNotStopTheRestOfTheSweep() {
        UUID failing = UUID.randomUUID();
        UUID succeeding = UUID.randomUUID();
        Settlement failingSettlement = mock(Settlement.class);
        when(failingSettlement.getId()).thenReturn(failing);
        Settlement succeedingSettlement = mock(Settlement.class);
        when(succeedingSettlement.getId()).thenReturn(succeeding);
        when(settlementRepository.findByStatusAndExternalRefIsNullAndUpdatedAtBefore(eq(SettlementStatus.PENDING), any()))
                .thenReturn(List.of(failingSettlement, succeedingSettlement));
        when(settlementTransactions.finalizeSettlement(eq(failing), any()))
                .thenThrow(new RuntimeException("boom"));

        sweepService.runOnce();

        verify(settlementTransactions).finalizeSettlement(eq(succeeding), any());
    }

    @Test
    void noCandidatesDoesNothing() {
        when(settlementRepository.findByStatusAndExternalRefIsNullAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of());

        sweepService.runOnce();

        verify(settlementTransactions, never()).finalizeSettlement(any(), any());
    }
}
