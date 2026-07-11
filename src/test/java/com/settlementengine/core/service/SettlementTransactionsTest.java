package com.settlementengine.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.settlementengine.core.domain.AccountNotFoundException;
import com.settlementengine.core.domain.CurrencyMismatchException;
import com.settlementengine.core.domain.IdempotencyKey;
import com.settlementengine.core.domain.IdempotencyKeyStatus;
import com.settlementengine.core.domain.InsufficientBalanceException;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.domain.LedgerEntry;
import com.settlementengine.core.domain.SelfSettlementException;
import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.events.KafkaTopics;
import com.settlementengine.core.gateway.GatewayResult;
import com.settlementengine.core.gateway.SettlementExecutionRequest;
import com.settlementengine.core.gateway.SettlementOutcome;
import com.settlementengine.core.outbox.OutboxWriter;
import com.settlementengine.core.repository.IdempotencyKeyRepository;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.repository.LedgerEntryRepository;
import com.settlementengine.core.repository.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementTransactionsTest {

    @Mock
    private LedgerAccountRepository ledgerAccountRepository;
    @Mock
    private SettlementRepository settlementRepository;
    @Mock
    private LedgerEntryRepository ledgerEntryRepository;
    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock
    private OutboxWriter outboxWriter;

    private SettlementTransactions settlementTransactions;

    private UUID sourceId;
    private UUID destinationId;
    private LedgerAccount source;
    private LedgerAccount destination;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        settlementTransactions = new SettlementTransactions(
                ledgerAccountRepository, settlementRepository, ledgerEntryRepository, idempotencyKeyRepository,
                objectMapper, outboxWriter);

        sourceId = UUID.randomUUID();
        destinationId = UUID.randomUUID();
        source = new LedgerAccount(sourceId, UUID.randomUUID(), new BigDecimal("100.00"), "USD");
        destination = new LedgerAccount(destinationId, UUID.randomUUID(), new BigDecimal("0.00"), "USD");
    }

    private CreateSettlementCommand command(BigDecimal amount) {
        return new CreateSettlementCommand(sourceId, destinationId, amount, "USD");
    }

    @Test
    void createPendingSettlementRejectsMissingSourceAccount() {
        when(ledgerAccountRepository.findById(sourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementTransactions.createPendingSettlement(
                UUID.randomUUID(), "hash", command(new BigDecimal("10.00"))))
                .isInstanceOf(AccountNotFoundException.class);

        verify(idempotencyKeyRepository, never()).saveAndFlush(any());
        verify(settlementRepository, never()).save(any());
    }

    @Test
    void createPendingSettlementRejectsSelfSettlement() {
        when(ledgerAccountRepository.findById(sourceId)).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> settlementTransactions.createPendingSettlement(
                UUID.randomUUID(), "hash", new CreateSettlementCommand(sourceId, sourceId, new BigDecimal("10.00"), "USD")))
                .isInstanceOf(SelfSettlementException.class);
    }

    @Test
    void createPendingSettlementRejectsCurrencyMismatch() {
        when(ledgerAccountRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(ledgerAccountRepository.findById(destinationId)).thenReturn(Optional.of(destination));

        assertThatThrownBy(() -> settlementTransactions.createPendingSettlement(
                UUID.randomUUID(), "hash", new CreateSettlementCommand(sourceId, destinationId, new BigDecimal("10.00"), "KES")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void createPendingSettlementRejectsInsufficientBalance() {
        when(ledgerAccountRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(ledgerAccountRepository.findById(destinationId)).thenReturn(Optional.of(destination));

        assertThatThrownBy(() -> settlementTransactions.createPendingSettlement(
                UUID.randomUUID(), "hash", command(new BigDecimal("999.00"))))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(idempotencyKeyRepository, never()).saveAndFlush(any());
    }

    @Test
    void createPendingSettlementPersistsKeyAndPendingSettlement() {
        when(ledgerAccountRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(ledgerAccountRepository.findById(destinationId)).thenReturn(Optional.of(destination));
        UUID idempotencyKey = UUID.randomUUID();

        SettlementExecutionRequest result = settlementTransactions.createPendingSettlement(
                idempotencyKey, "hash-1", command(new BigDecimal("40.00")));

        assertThat(result.sourceAccountId()).isEqualTo(sourceId);
        assertThat(result.destinationAccountId()).isEqualTo(destinationId);
        assertThat(result.amount()).isEqualByComparingTo("40.00");

        ArgumentCaptor<IdempotencyKey> keyCaptor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(idempotencyKeyRepository).saveAndFlush(keyCaptor.capture());
        assertThat(keyCaptor.getValue().getStatus()).isEqualTo(IdempotencyKeyStatus.IN_PROGRESS);
        assertThat(keyCaptor.getValue().getRequestHash()).isEqualTo("hash-1");

        ArgumentCaptor<Settlement> settlementCaptor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository).save(settlementCaptor.capture());
        assertThat(settlementCaptor.getValue().getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(settlementCaptor.getValue().getIdempotencyKey()).isEqualTo(idempotencyKey);

        verify(outboxWriter).write(eq("SETTLEMENT"), any(), eq(KafkaTopics.SETTLEMENT_REQUESTED), any());
    }

    @Test
    void finalizeSettlementConfirmedDebitsCreditsAndWritesBalancedLedgerEntries() {
        UUID settlementId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        Settlement settlement = new Settlement(settlementId, idempotencyKey, sourceId, destinationId,
                new BigDecimal("40.00"), "USD");
        IdempotencyKey key = new IdempotencyKey(idempotencyKey, "hash-1");

        when(settlementRepository.findById(settlementId)).thenReturn(Optional.of(settlement));
        when(ledgerAccountRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(ledgerAccountRepository.findById(destinationId)).thenReturn(Optional.of(destination));
        when(idempotencyKeyRepository.findById(idempotencyKey)).thenReturn(Optional.of(key));

        SettlementResult result = settlementTransactions.finalizeSettlement(settlementId,
                new GatewayResult(SettlementOutcome.CONFIRMED, "MOCK-ref-1"));

        assertThat(result.status()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(source.getBalance()).isEqualByComparingTo("60.00");
        assertThat(destination.getBalance()).isEqualByComparingTo("40.00");
        assertThat(settlement.getExternalRef()).isEqualTo("MOCK-ref-1");

        ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(entryCaptor.capture());
        BigDecimal netEffect = entryCaptor.getAllValues().stream()
                .map(entry -> entry.getEntryType().name().equals("DEBIT") ? entry.getAmount().negate() : entry.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(netEffect).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(key.getStatus()).isEqualTo(IdempotencyKeyStatus.COMPLETED);
        assertThat(key.getResponseSnapshot()).contains("CONFIRMED");

        verify(outboxWriter).write(eq("SETTLEMENT"), eq(settlementId), eq(KafkaTopics.SETTLEMENT_CONFIRMED), any());
    }

    @Test
    void finalizeSettlementFailedWritesNoLedgerEntriesAndNoBalanceChange() {
        UUID settlementId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        Settlement settlement = new Settlement(settlementId, idempotencyKey, sourceId, destinationId,
                new BigDecimal("40.00"), "USD");
        IdempotencyKey key = new IdempotencyKey(idempotencyKey, "hash-1");

        when(settlementRepository.findById(settlementId)).thenReturn(Optional.of(settlement));
        when(idempotencyKeyRepository.findById(idempotencyKey)).thenReturn(Optional.of(key));

        SettlementResult result = settlementTransactions.finalizeSettlement(settlementId,
                new GatewayResult(SettlementOutcome.FAILED, "MOCK-ref-2"));

        assertThat(result.status()).isEqualTo(SettlementStatus.FAILED);
        verify(ledgerEntryRepository, never()).save(any());
        verify(ledgerAccountRepository, never()).save(any());
        assertThat(key.getStatus()).isEqualTo(IdempotencyKeyStatus.COMPLETED);

        verify(outboxWriter).write(eq("SETTLEMENT"), eq(settlementId), eq(KafkaTopics.SETTLEMENT_FAILED), any());
    }

    @Test
    void finalizeSettlementUnknownWritesNoLedgerEntriesAndLeavesKeyCompletedWithUnknownState() {
        UUID settlementId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        Settlement settlement = new Settlement(settlementId, idempotencyKey, sourceId, destinationId,
                new BigDecimal("40.00"), "USD");
        IdempotencyKey key = new IdempotencyKey(idempotencyKey, "hash-1");

        when(settlementRepository.findById(settlementId)).thenReturn(Optional.of(settlement));
        when(idempotencyKeyRepository.findById(idempotencyKey)).thenReturn(Optional.of(key));

        SettlementResult result = settlementTransactions.finalizeSettlement(settlementId,
                new GatewayResult(SettlementOutcome.UNKNOWN, null));

        assertThat(result.status()).isEqualTo(SettlementStatus.UNKNOWN);
        verify(ledgerEntryRepository, never()).save(any());

        verify(outboxWriter).write(eq("SETTLEMENT"), eq(settlementId), eq(KafkaTopics.SETTLEMENT_UNKNOWN), any());
    }
}
