package com.settlementengine.core.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementTest {

    private Settlement newSettlement() {
        return new Settlement(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("100.00"),
                "USD");
    }

    @Test
    void startsInPendingState() {
        Settlement settlement = newSettlement();

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
    }

    @Test
    void allowsValidTransition() {
        Settlement settlement = newSettlement();

        settlement.transitionTo(SettlementStatus.CONFIRMED);

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
    }

    @Test
    void rejectsInvalidTransition() {
        Settlement settlement = newSettlement();
        settlement.transitionTo(SettlementStatus.FAILED);

        assertThatThrownBy(() -> settlement.transitionTo(SettlementStatus.CONFIRMED))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.FAILED);
    }

    @Test
    void unknownCanOnlyResolveThroughReconciliation() {
        Settlement settlement = newSettlement();
        settlement.transitionTo(SettlementStatus.UNKNOWN);

        assertThatThrownBy(() -> settlement.transitionTo(SettlementStatus.REVERSED))
                .isInstanceOf(IllegalStateTransitionException.class);

        settlement.transitionTo(SettlementStatus.CONFIRMED);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
    }
}
