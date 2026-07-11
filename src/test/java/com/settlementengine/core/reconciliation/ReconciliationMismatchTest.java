package com.settlementengine.core.reconciliation;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationMismatchTest {

    private ReconciliationMismatch mismatch() {
        return new ReconciliationMismatch(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "CONFIRMED", "FAILED", "external system reports FAILED, internal ledger already CONFIRMED");
    }

    @Test
    void startsOpen() {
        ReconciliationMismatch mismatch = mismatch();

        assertThat(mismatch.getResolutionStatus()).isEqualTo(MismatchResolutionStatus.OPEN);
        assertThat(mismatch.getResolvedAt()).isNull();
    }

    @Test
    void resolveMarksResolvedAndAppendsNote() {
        ReconciliationMismatch mismatch = mismatch();

        mismatch.resolve("Confirmed with provider support, ledger entry corrected manually");

        assertThat(mismatch.getResolutionStatus()).isEqualTo(MismatchResolutionStatus.RESOLVED);
        assertThat(mismatch.getResolvedAt()).isNotNull();
        assertThat(mismatch.getDetails()).contains("Confirmed with provider support");
    }

    @Test
    void resolvingTwiceThrows() {
        ReconciliationMismatch mismatch = mismatch();
        mismatch.resolve("first resolution");

        assertThatThrownBy(() -> mismatch.resolve("second resolution"))
                .isInstanceOf(MismatchAlreadyResolvedException.class);
    }
}
