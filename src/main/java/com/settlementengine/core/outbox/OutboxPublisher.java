package com.settlementengine.core.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                            KafkaTemplate<String, String> kafkaTemplate,
                            @Value("${settlement-engine.outbox.batch-size:100}") int batchSize) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${settlement-engine.outbox.poll-fixed-delay-ms:500}")
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(Limit.of(batchSize));
        for (OutboxEvent event : pending) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            // Blocking on ack (acks=all, idempotent producer) before marking published: an
            // unpublished row is always safe to retry, but marking published too early on a send
            // that never lands would silently drop the event.
            kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload()).get();
            event.markPublished();
            outboxEventRepository.save(event);
        } catch (Exception e) {
            log.warn("Failed to publish outbox event {} to topic {}, will retry next poll",
                    event.getId(), event.getTopic(), e);
        }
    }
}
