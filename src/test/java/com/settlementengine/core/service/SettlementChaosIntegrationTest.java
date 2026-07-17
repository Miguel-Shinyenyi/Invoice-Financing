package com.settlementengine.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlementengine.core.AbstractIntegrationTest;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.events.KafkaTopics;
import com.settlementengine.core.events.SettlementConfirmedEvent;
import com.settlementengine.core.gateway.ExternalSettlementGateway;
import com.settlementengine.core.gateway.FaultInjectableExternalSettlementGateway;
import com.settlementengine.core.readmodel.SettlementReadModel;
import com.settlementengine.core.readmodel.SettlementReadModelRepository;
import com.settlementengine.core.repository.LedgerAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the project's core claim -- an internal ledger stays consistent with an external system
 * that communicates asynchronously and unreliably -- under actually injected failure, not just
 * the normal path every other integration test exercises. See docs/testing.md.
 */
class SettlementChaosIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    @Autowired
    private SettlementTransactions settlementTransactions;
    @Autowired
    private RequestHasher requestHasher;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExternalSettlementGateway realGateway;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private SettlementReadModelRepository settlementReadModelRepository;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private SettlementService settlementServiceWith(FaultInjectableExternalSettlementGateway gateway) {
        return new SettlementService(settlementTransactions, gateway, requestHasher, objectMapper);
    }

    private LedgerAccount account(BigDecimal balance) {
        return ledgerAccountRepository.save(new LedgerAccount(UUID.randomUUID(), UUID.randomUUID(), balance, "USD"));
    }

    @Test
    void networkFailureAtTheGatewayResolvesToUnknownInsteadOfStayingStuckForever() {
        FaultInjectableExternalSettlementGateway faultyGateway = new FaultInjectableExternalSettlementGateway(realGateway);
        faultyGateway.throwOnNextCall();
        SettlementService settlementService = settlementServiceWith(faultyGateway);

        LedgerAccount source = account(new BigDecimal("500.00"));
        LedgerAccount destination = account(new BigDecimal("0.00"));
        CreateSettlementCommand command = new CreateSettlementCommand(source.getId(), destination.getId(),
                new BigDecimal("50.00"), "USD");
        UUID idempotencyKey = UUID.randomUUID();

        SettlementResult firstAttempt = settlementService.createSettlement(idempotencyKey, command);
        assertThat(firstAttempt.status()).isEqualTo(SettlementStatus.UNKNOWN);

        // The idempotency key must have completed, not stayed IN_PROGRESS -- a retry with the
        // same key returns the cached UNKNOWN result instead of 409ing forever.
        SettlementResult retry = settlementService.createSettlement(idempotencyKey, command);
        assertThat(retry.settlementId()).isEqualTo(firstAttempt.settlementId());
        assertThat(retry.status()).isEqualTo(SettlementStatus.UNKNOWN);

        // No ledger movement happened -- UNKNOWN never touches balances, only CONFIRMED does.
        LedgerAccount sourceAfter = ledgerAccountRepository.findById(source.getId()).orElseThrow();
        assertThat(sourceAfter.getBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void concurrentRetriesOfTheSameIdempotencyKeyAgainstASlowGatewayProduceExactlyOneSettlement() throws Exception {
        FaultInjectableExternalSettlementGateway faultyGateway = new FaultInjectableExternalSettlementGateway(realGateway);
        faultyGateway.delayEveryCallBy(300);
        SettlementService settlementService = settlementServiceWith(faultyGateway);

        LedgerAccount source = account(new BigDecimal("500.00"));
        LedgerAccount destination = account(new BigDecimal("0.00"));
        CreateSettlementCommand command = new CreateSettlementCommand(source.getId(), destination.getId(),
                new BigDecimal("50.00"), "USD");
        UUID idempotencyKey = UUID.randomUUID();

        // Simulates a client that gave up waiting on a slow response and retried with the same
        // idempotency key while the first attempt was still in flight against the gateway.
        int concurrentAttempts = 5;
        ExecutorService pool = Executors.newFixedThreadPool(concurrentAttempts);
        CountDownLatch ready = new CountDownLatch(concurrentAttempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < concurrentAttempts; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    settlementService.createSettlement(idempotencyKey, command);
                    successes.incrementAndGet();
                } catch (Exception inProgressOrOtherwise) {
                    conflicts.incrementAndGet();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(successes.get() + conflicts.get()).isEqualTo(concurrentAttempts);
        // Exactly one settlement, one pair of ledger entries -- balance moved by 50.00, not
        // 50.00 * however many attempts raced through.
        LedgerAccount sourceAfter = ledgerAccountRepository.findById(source.getId()).orElseThrow();
        LedgerAccount destinationAfter = ledgerAccountRepository.findById(destination.getId()).orElseThrow();
        assertThat(sourceAfter.getBalance()).isEqualByComparingTo("450.00");
        assertThat(destinationAfter.getBalance()).isEqualByComparingTo("50.00");
    }

    @Test
    void duplicateKafkaDeliveryOfTheSameEventLeavesTheReadModelUnchanged() throws Exception {
        UUID settlementId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        String payload = objectMapper.writeValueAsString(new SettlementConfirmedEvent(
                settlementId, sourceId, destinationId, new BigDecimal("75.00"), "USD", occurredAt));

        // Simulates an at-least-once broker redelivery (e.g. consumer crash before offset commit)
        // by sending the exact same message twice, independent of the outbox's own publish path.
        kafkaTemplate.send(KafkaTopics.SETTLEMENT_CONFIRMED, settlementId.toString(), payload)
                .get(10, TimeUnit.SECONDS);
        SettlementReadModel afterFirst = awaitReadModel(settlementId);
        assertThat(afterFirst.getStatus()).isEqualTo("CONFIRMED");

        kafkaTemplate.send(KafkaTopics.SETTLEMENT_CONFIRMED, settlementId.toString(), payload)
                .get(10, TimeUnit.SECONDS);
        // Give the second delivery time to be consumed; there's nothing new to await for since
        // the expected outcome is "nothing changes".
        Thread.sleep(2000);

        SettlementReadModel afterSecond = settlementReadModelRepository.findById(settlementId).orElseThrow();
        assertThat(afterSecond.getStatus()).isEqualTo("CONFIRMED");
        assertThat(afterSecond.getUpdatedAt()).isEqualTo(afterFirst.getUpdatedAt());
        assertThat(afterSecond.getAmount()).isEqualByComparingTo(afterFirst.getAmount());
    }

    private SettlementReadModel awaitReadModel(UUID settlementId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            Optional<SettlementReadModel> found = settlementReadModelRepository.findById(settlementId);
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Read model for settlement " + settlementId + " never appeared in time");
    }
}
