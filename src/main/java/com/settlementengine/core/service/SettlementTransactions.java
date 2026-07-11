package com.settlementengine.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlementengine.core.domain.CurrencyMismatchException;
import com.settlementengine.core.domain.AccountNotFoundException;
import com.settlementengine.core.domain.IdempotencyKey;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.domain.LedgerEntry;
import com.settlementengine.core.domain.EntryType;
import com.settlementengine.core.domain.SelfSettlementException;
import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.gateway.SettlementExecutionRequest;
import com.settlementengine.core.gateway.SettlementOutcome;
import com.settlementengine.core.repository.IdempotencyKeyRepository;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.repository.LedgerEntryRepository;
import com.settlementengine.core.repository.SettlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class SettlementTransactions {

    private final LedgerAccountRepository ledgerAccountRepository;
    private final SettlementRepository settlementRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    public SettlementTransactions(LedgerAccountRepository ledgerAccountRepository,
                                   SettlementRepository settlementRepository,
                                   LedgerEntryRepository ledgerEntryRepository,
                                   IdempotencyKeyRepository idempotencyKeyRepository,
                                   ObjectMapper objectMapper) {
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.settlementRepository = settlementRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyKey> findExisting(UUID idempotencyKey) {
        return idempotencyKeyRepository.findById(idempotencyKey);
    }

    @Transactional
    public SettlementExecutionRequest createPendingSettlement(UUID idempotencyKey, String requestHash,
                                                                CreateSettlementCommand command) {
        LedgerAccount source = ledgerAccountRepository.findById(command.sourceAccountId())
                .orElseThrow(() -> new AccountNotFoundException(command.sourceAccountId()));
        LedgerAccount destination = ledgerAccountRepository.findById(command.destinationAccountId())
                .orElseThrow(() -> new AccountNotFoundException(command.destinationAccountId()));

        validate(source, destination, command);

        idempotencyKeyRepository.saveAndFlush(new IdempotencyKey(idempotencyKey, requestHash));

        UUID settlementId = UUID.randomUUID();
        Settlement settlement = new Settlement(settlementId, idempotencyKey, source.getId(), destination.getId(),
                command.amount(), command.currency());
        settlementRepository.save(settlement);

        return new SettlementExecutionRequest(settlementId, source.getId(), destination.getId(),
                command.amount(), command.currency());
    }

    @Transactional
    public SettlementResult finalizeSettlement(UUID settlementId, SettlementOutcome outcome) {
        Settlement settlement = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new IllegalStateException("Settlement " + settlementId + " not found during finalize"));
        settlement.transitionTo(outcome.toSettlementStatus());

        if (outcome == SettlementOutcome.CONFIRMED) {
            LedgerAccount source = ledgerAccountRepository.findById(settlement.getSourceAccountId())
                    .orElseThrow(() -> new IllegalStateException("Source account missing during finalize"));
            LedgerAccount destination = ledgerAccountRepository.findById(settlement.getDestinationAccountId())
                    .orElseThrow(() -> new IllegalStateException("Destination account missing during finalize"));

            source.debit(settlement.getAmount());
            destination.credit(settlement.getAmount());
            ledgerAccountRepository.save(source);
            ledgerAccountRepository.save(destination);

            ledgerEntryRepository.save(new LedgerEntry(UUID.randomUUID(), settlementId, source.getId(),
                    EntryType.DEBIT, settlement.getAmount()));
            ledgerEntryRepository.save(new LedgerEntry(UUID.randomUUID(), settlementId, destination.getId(),
                    EntryType.CREDIT, settlement.getAmount()));
        }

        settlementRepository.save(settlement);

        SettlementResult result = SettlementResult.from(settlement);
        IdempotencyKey key = idempotencyKeyRepository.findById(settlement.getIdempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Idempotency key missing during finalize"));
        key.complete(serialize(result));
        idempotencyKeyRepository.save(key);

        return result;
    }

    private void validate(LedgerAccount source, LedgerAccount destination, CreateSettlementCommand command) {
        if (source.getId().equals(destination.getId())) {
            throw new SelfSettlementException(source.getId());
        }
        if (!source.getCurrency().equals(command.currency()) || !destination.getCurrency().equals(command.currency())) {
            throw new CurrencyMismatchException(
                    "Settlement currency %s does not match account currencies (source: %s, destination: %s)"
                            .formatted(command.currency(), source.getCurrency(), destination.getCurrency()));
        }
        if (source.getBalance().compareTo(command.amount()) < 0) {
            throw new com.settlementengine.core.domain.InsufficientBalanceException(
                    source.getId(), source.getBalance(), command.amount());
        }
    }

    private String serialize(SettlementResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize settlement result", e);
        }
    }
}
