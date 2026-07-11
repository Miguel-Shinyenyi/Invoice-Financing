package com.settlementengine.core.invoicing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdvanceTest {

    private Advance newAdvance() {
        return new Advance(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("800.00"),
                new BigDecimal("20.00"), UUID.randomUUID());
    }

    @Test
    void startsDisbursed() {
        Advance advance = newAdvance();

        assertThat(advance.getStatus()).isEqualTo(AdvanceStatus.DISBURSED);
        assertThat(advance.getRepaidSettlementId()).isNull();
    }

    @Test
    void markRepaidSetsRepaidSettlementIdAndStatus() {
        Advance advance = newAdvance();
        UUID repaidSettlementId = UUID.randomUUID();

        advance.markRepaid(repaidSettlementId);

        assertThat(advance.getStatus()).isEqualTo(AdvanceStatus.REPAID);
        assertThat(advance.getRepaidSettlementId()).isEqualTo(repaidSettlementId);
    }

    @Test
    void rejectsInvalidTransition() {
        Advance advance = newAdvance();
        advance.markRepaid(UUID.randomUUID());

        assertThatThrownBy(() -> advance.markRepaid(UUID.randomUUID()))
                .isInstanceOf(AdvanceTransitionException.class);
    }
}
