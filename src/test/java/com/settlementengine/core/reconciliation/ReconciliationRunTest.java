package com.settlementengine.core.reconciliation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationRunTest {

    @Test
    void startsRunning() {
        ReconciliationRun run = new ReconciliationRun(UUID.randomUUID(), Instant.now());

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.RUNNING);
        assertThat(run.getFinishedAt()).isNull();
    }

    @Test
    void completeRecordsCountsAndFinishTime() {
        ReconciliationRun run = new ReconciliationRun(UUID.randomUUID(), Instant.now());

        run.complete(10, 2);

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(run.getRecordsChecked()).isEqualTo(10);
        assertThat(run.getMismatchesFound()).isEqualTo(2);
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void failMarksFailedWithFinishTime() {
        ReconciliationRun run = new ReconciliationRun(UUID.randomUUID(), Instant.now());

        run.fail();

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.FAILED);
        assertThat(run.getFinishedAt()).isNotNull();
    }
}
