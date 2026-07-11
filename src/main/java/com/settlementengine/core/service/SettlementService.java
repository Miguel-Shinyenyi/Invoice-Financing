package com.settlementengine.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlementengine.core.domain.IdempotencyKey;
import com.settlementengine.core.domain.IdempotencyKeyReusedException;
import com.settlementengine.core.domain.IdempotencyKeyStatus;
import com.settlementengine.core.domain.SettlementInProgressException;
import com.settlementengine.core.gateway.ExternalSettlementGateway;
import com.settlementengine.core.gateway.SettlementExecutionRequest;
import com.settlementengine.core.gateway.SettlementOutcome;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class SettlementService {

    private final SettlementTransactions settlementTransactions;
    private final ExternalSettlementGateway externalSettlementGateway;
    private final RequestHasher requestHasher;
    private final ObjectMapper objectMapper;

    public SettlementService(SettlementTransactions settlementTransactions,
                              ExternalSettlementGateway externalSettlementGateway,
                              RequestHasher requestHasher,
                              ObjectMapper objectMapper) {
        this.settlementTransactions = settlementTransactions;
        this.externalSettlementGateway = externalSettlementGateway;
        this.requestHasher = requestHasher;
        this.objectMapper = objectMapper;
    }

    public SettlementResult createSettlement(UUID idempotencyKey, CreateSettlementCommand command) {
        String requestHash = requestHasher.hash(command);

        Optional<IdempotencyKey> existing = settlementTransactions.findExisting(idempotencyKey);
        if (existing.isPresent()) {
            return handleExisting(existing.get(), requestHash);
        }

        SettlementExecutionRequest executionRequest;
        try {
            executionRequest = settlementTransactions.createPendingSettlement(idempotencyKey, requestHash, command);
        } catch (DataAccessException raceLost) {
            // Concurrent inserts for the same idempotency key can surface as a clean unique-constraint
            // violation or, under Postgres, as a deadlock between two competing index insertions.
            // Either way it means someone else claimed this key first.
            IdempotencyKey winner = settlementTransactions.findExisting(idempotencyKey)
                    .orElseThrow(() -> raceLost);
            return handleExisting(winner, requestHash);
        }

        SettlementOutcome outcome = externalSettlementGateway.execute(executionRequest);
        return settlementTransactions.finalizeSettlement(executionRequest.settlementId(), outcome);
    }

    private SettlementResult handleExisting(IdempotencyKey existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(existing.getKey());
        }
        if (existing.getStatus() == IdempotencyKeyStatus.IN_PROGRESS) {
            throw new SettlementInProgressException(existing.getKey());
        }
        return deserialize(existing.getResponseSnapshot());
    }

    private SettlementResult deserialize(String json) {
        try {
            return objectMapper.readValue(json, SettlementResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to deserialize cached settlement result", e);
        }
    }
}
