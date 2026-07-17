package com.settlementengine.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlementengine.core.domain.IdempotencyKey;
import com.settlementengine.core.domain.IdempotencyKeyReusedException;
import com.settlementengine.core.domain.IdempotencyKeyStatus;
import com.settlementengine.core.domain.SettlementInProgressException;
import com.settlementengine.core.gateway.ExternalSettlementGateway;
import com.settlementengine.core.gateway.GatewayResult;
import com.settlementengine.core.gateway.SettlementExecutionRequest;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
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

        MDC.put("settlementId", executionRequest.settlementId().toString());
        try {
            GatewayResult gatewayResult = externalSettlementGateway.execute(executionRequest);
            return finalizeWithRetry(executionRequest.settlementId(), gatewayResult);
        } finally {
            MDC.remove("settlementId");
        }
    }

    private static final int MAX_FINALIZE_ATTEMPTS = 5;

    private SettlementResult finalizeWithRetry(UUID settlementId, GatewayResult gatewayResult) {
        // The settlement row is already committed as PENDING by this point, so retrying only
        // finalizeSettlement (not the whole createSettlement flow) is safe: other concurrent
        // callers for the same idempotency key still see IN_PROGRESS while we retry, and each
        // attempt re-reads fresh state in its own transaction. This specifically handles Postgres
        // deadlocking the winner's terminal-state update against other losing transactions still
        // holding a FK-check lock on the same idempotency_keys row from their own (doomed) insert
        // attempts — a transient condition, not a correctness problem.
        for (int attempt = 1; attempt <= MAX_FINALIZE_ATTEMPTS; attempt++) {
            try {
                return settlementTransactions.finalizeSettlement(settlementId, gatewayResult);
            } catch (TransientDataAccessException transientFailure) {
                if (attempt == MAX_FINALIZE_ATTEMPTS) {
                    throw transientFailure;
                }
                sleepBriefly(attempt);
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    private void sleepBriefly(int attempt) {
        try {
            Thread.sleep(20L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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
