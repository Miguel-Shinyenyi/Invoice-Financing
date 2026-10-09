package com.settlementengine.core.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyKeyTest {

    @Test
    void startsInProgress() {
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID(), "hash-1");

        assertThat(key.getStatus()).isEqualTo(IdempotencyKeyStatus.IN_PROGRESS);
        assertThat(key.getResponseSnapshot()).isNull();
    }

    @Test
    void completeStoresSnapshotAndMarksCompleted() {
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID(), "hash-1");

        key.complete("{\"settlementId\":\"abc\"}");

        assertThat(key.getStatus()).isEqualTo(IdempotencyKeyStatus.COMPLETED);
        assertThat(key.getResponseSnapshot()).isEqualTo("{\"settlementId\":\"abc\"}");
    }
}
