package com.settlementengine.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.settlementengine.core.domain.IdempotencyKey;
import com.settlementengine.core.domain.IdempotencyKeyReusedException;
import com.settlementengine.core.domain.SettlementInProgressException;
import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.gateway.ExternalSettlementGateway;
import com.settlementengine.core.gateway.GatewayResult;
import com.settlementengine.core.gateway.SettlementExecutionRequest;
import com.settlementengine.core.gateway.SettlementOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock
    private SettlementTransactions settlementTransactions;
    @Mock
    private ExternalSettlementGateway externalSettlementGateway;

    private SettlementService settlementService;

    private UUID idempotencyKey;
    private CreateSettlementCommand command;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RequestHasher requestHasher = new RequestHasher(objectMapper);
        settlementService = new SettlementService(settlementTransactions, externalSettlementGateway, requestHasher, objectMapper);

        idempotencyKey = UUID.randomUUID();
        command = new CreateSettlementCommand(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), "USD");
    }

    @Test
    void freshRequestCreatesSettlementCallsGatewayAndFinalizes() {
        when(settlementTransactions.findExisting(idempotencyKey)).thenReturn(Optional.empty());
        SettlementExecutionRequest executionRequest = new SettlementExecutionRequest(
                UUID.randomUUID(), command.sourceAccountId(), command.destinationAccountId(), command.amount(), command.currency());
        when(settlementTransactions.createPendingSettlement(any(), any(), any())).thenReturn(executionRequest);
        GatewayResult gatewayResult = new GatewayResult(SettlementOutcome.CONFIRMED, "MOCK-ref");
        when(externalSettlementGateway.execute(executionRequest)).thenReturn(gatewayResult);
        SettlementResult finalResult = new SettlementResult(executionRequest.settlementId(), command.sourceAccountId(),
                command.destinationAccountId(), command.amount(), command.currency(), SettlementStatus.CONFIRMED,
                null, Instant.now(), Instant.now());
        when(settlementTransactions.finalizeSettlement(executionRequest.settlementId(), gatewayResult))
                .thenReturn(finalResult);

        SettlementResult result = settlementService.createSettlement(idempotencyKey, command);

        assertThat(result.status()).isEqualTo(SettlementStatus.CONFIRMED);
        verify(externalSettlementGateway).execute(executionRequest);
    }

    @Test
    void completedDuplicateReturnsCachedResultWithoutCallingGatewayOrCreating() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RequestHasher requestHasher = new RequestHasher(objectMapper);
        String requestHash = requestHasher.hash(command);

        SettlementResult cached = new SettlementResult(UUID.randomUUID(), command.sourceAccountId(),
                command.destinationAccountId(), command.amount(), command.currency(), SettlementStatus.CONFIRMED,
                null, Instant.now(), Instant.now());
        IdempotencyKey existingKey = new IdempotencyKey(idempotencyKey, requestHash);
        try {
            existingKey.complete(objectMapper.writeValueAsString(cached));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        when(settlementTransactions.findExisting(idempotencyKey)).thenReturn(Optional.of(existingKey));

        SettlementResult result = settlementService.createSettlement(idempotencyKey, command);

        assertThat(result.settlementId()).isEqualTo(cached.settlementId());
        verify(externalSettlementGateway, never()).execute(any());
        verify(settlementTransactions, never()).createPendingSettlement(any(), any(), any());
    }

    @Test
    void inProgressDuplicateThrowsConflict() {
        RequestHasher requestHasher = new RequestHasher(new ObjectMapper().registerModule(new JavaTimeModule()));
        String requestHash = requestHasher.hash(command);
        IdempotencyKey inProgressKey = new IdempotencyKey(idempotencyKey, requestHash);

        when(settlementTransactions.findExisting(idempotencyKey)).thenReturn(Optional.of(inProgressKey));

        assertThatThrownBy(() -> settlementService.createSettlement(idempotencyKey, command))
                .isInstanceOf(SettlementInProgressException.class);

        verify(externalSettlementGateway, never()).execute(any());
    }

    @Test
    void sameKeyWithDifferentPayloadThrowsReusedException() {
        IdempotencyKey existingKey = new IdempotencyKey(idempotencyKey, "a-different-hash");
        existingKey.complete("{}");

        when(settlementTransactions.findExisting(idempotencyKey)).thenReturn(Optional.of(existingKey));

        assertThatThrownBy(() -> settlementService.createSettlement(idempotencyKey, command))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void concurrentInsertRaceFallsBackToWinningRecord() {
        when(settlementTransactions.findExisting(idempotencyKey))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(settlementTransactions.createPendingSettlement(any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        RequestHasher requestHasher = new RequestHasher(new ObjectMapper().registerModule(new JavaTimeModule()));
        String requestHash = requestHasher.hash(command);
        SettlementResult cached = new SettlementResult(UUID.randomUUID(), command.sourceAccountId(),
                command.destinationAccountId(), command.amount(), command.currency(), SettlementStatus.PENDING,
                null, Instant.now(), Instant.now());
        IdempotencyKey winner = new IdempotencyKey(idempotencyKey, requestHash);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try {
            winner.complete(objectMapper.writeValueAsString(cached));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(settlementTransactions.findExisting(idempotencyKey))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));

        SettlementResult result = settlementService.createSettlement(idempotencyKey, command);

        assertThat(result.settlementId()).isEqualTo(cached.settlementId());
        verify(externalSettlementGateway, never()).execute(any());
    }
}
