package com.settlementengine.core.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerEntryTest {

    @Test
    void openingEntryHasNoSettlement() {
        LedgerEntry entry = LedgerEntry.opening(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("50.00"));

        assertThat(entry.getEntryType()).isEqualTo(EntryType.OPENING);
        assertThat(entry.getSettlementId()).isNull();
    }

    @Test
    void settlementConstructorRejectsOpeningType() {
        assertThatThrownBy(() -> new LedgerEntry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                EntryType.OPENING, new BigDecimal("50.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settlementConstructorRequiresSettlementId() {
        assertThatThrownBy(() -> new LedgerEntry(UUID.randomUUID(), null, UUID.randomUUID(),
                EntryType.DEBIT, new BigDecimal("50.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
