package com.settlementengine.core.readmodel;

import com.settlementengine.core.AbstractIntegrationTest;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.outbox.OutboxPublisher;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.service.CreateSettlementCommand;
import com.settlementengine.core.service.SettlementResult;
import com.settlementengine.core.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventDrivenIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    @Autowired
    private SettlementService settlementService;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private OutboxPublisher outboxPublisher;
    @Autowired
    private SettlementReadModelRepository settlementReadModelRepository;

    @Test
    void confirmedSettlementFlowsThroughOutboxKafkaAndUpdatesReadModel() throws Exception {
        LedgerAccount source = ledgerAccountRepository.save(
                new LedgerAccount(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("500.00"), "USD"));
        LedgerAccount destination = ledgerAccountRepository.save(
                new LedgerAccount(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("0.00"), "USD"));
        CreateSettlementCommand command = new CreateSettlementCommand(
                source.getId(), destination.getId(), new BigDecimal("75.00"), "USD");

        SettlementResult result = settlementService.createSettlement(UUID.randomUUID(), command);
        assertThat(result.status().name()).isEqualTo("CONFIRMED");

        // Drive the outbox->Kafka handoff directly rather than waiting on real @Scheduled timing.
        outboxPublisher.publishPending();

        SettlementReadModel readModel = awaitReadModel(result.settlementId());

        assertThat(readModel.getStatus()).isEqualTo("CONFIRMED");
        assertThat(readModel.getSourceAccountId()).isEqualTo(source.getId());
        assertThat(readModel.getDestinationAccountId()).isEqualTo(destination.getId());
        assertThat(readModel.getAmount()).isEqualByComparingTo("75.00");
        assertThat(readModel.getCurrency()).isEqualTo("USD");
    }

    private SettlementReadModel awaitReadModel(UUID settlementId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            Optional<SettlementReadModel> found = settlementReadModelRepository.findById(settlementId);
            if (found.isPresent() && "CONFIRMED".equals(found.get().getStatus())) {
                return found.get();
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Read model for settlement " + settlementId + " never reached CONFIRMED in time");
    }
}
