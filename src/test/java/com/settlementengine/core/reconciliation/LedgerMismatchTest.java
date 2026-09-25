package com.settlementengine.core.reconciliation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerMismatchTest {

    private LedgerMismatch mismatch() {
        return new LedgerMismatch(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"),
                new BigDecimal("90.00"), "Stored balance 100.00 does not match net of ledger entries 90.00");
    }

    @Test
    void startsOpen() {
        LedgerMismatch mismatch = mismatch();

        assertThat(mismatch.getResolutionStatus()).isEqualTo(MismatchResolutionStatus.OPEN);
        assertThat(mismatch.getResolvedAt()).isNull();
    }

    @Test
    void resolveMarksResolvedAndAppendsNote() {
        LedgerMismatch mismatch = mismatch();

        mismatch.resolve("Missing DEBIT entry traced to a manual DB edit");

        assertThat(mismatch.getResolutionStatus()).isEqualTo(MismatchResolutionStatus.RESOLVED);
        assertThat(mismatch.getResolvedAt()).isNotNull();
        assertThat(mismatch.getDetails()).contains("Missing DEBIT entry traced");
        assertThat(mismatch.getStoredBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void resolvingTwiceThrows() {
        LedgerMismatch mismatch = mismatch();
        mismatch.resolve("first resolution");

        assertThatThrownBy(() -> mismatch.resolve("second resolution"))
                .isInstanceOf(MismatchAlreadyResolvedException.class);
    }
}
