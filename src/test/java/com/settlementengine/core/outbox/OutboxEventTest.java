package com.settlementengine.core.outbox;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    @Test
    void startsUnpublished() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "SETTLEMENT", UUID.randomUUID(),
                "settlement.requested", "{}");

        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    void markPublishedSetsTimestamp() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "SETTLEMENT", UUID.randomUUID(),
                "settlement.requested", "{}");

        event.markPublished();

        assertThat(event.getPublishedAt()).isNotNull();
    }
}
