package com.settlementengine.core.service;

import com.settlementengine.core.AbstractIntegrationTest;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.domain.SettlementInProgressException;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.repository.LedgerEntryRepository;
import com.settlementengine.core.repository.SettlementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SettlementService settlementService;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private SettlementRepository settlementRepository;
    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void concurrentSubmissionsWithSameIdempotencyKeyProduceExactlyOneSettlement() throws Exception {
        LedgerAccount source = ledgerAccountRepository.save(
                new LedgerAccount(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), "USD"));
        LedgerAccount destination = ledgerAccountRepository.save(
                new LedgerAccount(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("0.00"), "USD"));
        UUID idempotencyKey = UUID.randomUUID();
        CreateSettlementCommand command = new CreateSettlementCommand(
                source.getId(), destination.getId(), new BigDecimal("100.00"), "USD");

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return settlementService.createSettlement(idempotencyKey, command);
                } catch (Exception e) {
                    return e;
                }
            }));
        }

        ready.await();
        start.countDown();

        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        executor.shutdown();

        List<SettlementResult> successes = outcomes.stream()
                .filter(SettlementResult.class::isInstance)
                .map(SettlementResult.class::cast)
                .toList();
        List<Object> failures = outcomes.stream()
                .filter(o -> !(o instanceof SettlementResult))
                .toList();

        assertThat(failures).allMatch(SettlementInProgressException.class::isInstance);
        assertThat(successes).isNotEmpty();

        Set<UUID> distinctSettlementIds = successes.stream()
                .map(SettlementResult::settlementId)
                .collect(Collectors.toSet());
        assertThat(distinctSettlementIds).hasSize(1);

        UUID settlementId = distinctSettlementIds.iterator().next();
        assertThat(settlementRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getSettlementId().equals(settlementId))
                .count()).isEqualTo(2);

        LedgerAccount refreshedSource = ledgerAccountRepository.findById(source.getId()).orElseThrow();
        LedgerAccount refreshedDestination = ledgerAccountRepository.findById(destination.getId()).orElseThrow();
        assertThat(refreshedSource.getBalance()).isEqualByComparingTo("900.00");
        assertThat(refreshedDestination.getBalance()).isEqualByComparingTo("100.00");
    }
}
