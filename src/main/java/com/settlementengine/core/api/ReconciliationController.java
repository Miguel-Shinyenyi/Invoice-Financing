package com.settlementengine.core.api;

import com.settlementengine.core.domain.AuditOutcome;
import com.settlementengine.core.events.KafkaTopics;
import com.settlementengine.core.events.ReconciliationResolvedEvent;
import com.settlementengine.core.outbox.OutboxWriter;
import com.settlementengine.core.reconciliation.LedgerMismatch;
import com.settlementengine.core.reconciliation.MismatchNotFoundException;
import com.settlementengine.core.reconciliation.MismatchResolutionStatus;
import com.settlementengine.core.reconciliation.ReconciliationMismatch;
import com.settlementengine.core.reconciliation.ReconciliationRun;
import com.settlementengine.core.reconciliation.ReconciliationService;
import com.settlementengine.core.repository.LedgerMismatchRepository;
import com.settlementengine.core.repository.ReconciliationMismatchRepository;
import com.settlementengine.core.security.AccessTokenClaims;
import com.settlementengine.core.security.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/reconciliation")
@Tag(name = "Reconciliation", description = "Trigger reconciliation runs and manage reconciliation and ledger mismatches")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final ReconciliationMismatchRepository reconciliationMismatchRepository;
    private final LedgerMismatchRepository ledgerMismatchRepository;
    private final OutboxWriter outboxWriter;
    private final AuditLogService auditLogService;

    public ReconciliationController(ReconciliationService reconciliationService,
                                     ReconciliationMismatchRepository reconciliationMismatchRepository,
                                     LedgerMismatchRepository ledgerMismatchRepository,
                                     OutboxWriter outboxWriter, AuditLogService auditLogService) {
        this.reconciliationService = reconciliationService;
        this.reconciliationMismatchRepository = reconciliationMismatchRepository;
        this.ledgerMismatchRepository = ledgerMismatchRepository;
        this.outboxWriter = outboxWriter;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/runs")
    @Operation(summary = "Trigger a reconciliation run",
            description = "Mainly for testing and admin use. The scheduled job is the primary trigger.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Run completed (or failed) and its summary returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not ADMIN or SUPPORT", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ReconciliationRunResponse triggerRun(@AuthenticationPrincipal AccessTokenClaims claims) {
        ReconciliationRun run = reconciliationService.runOnce();
        auditLogService.record(claims.userId(), "TRIGGER_RECONCILIATION_RUN", "reconciliation_runs", run.getId(),
                AuditOutcome.SUCCESS);
        return ReconciliationRunResponse.from(run);
    }

    @GetMapping("/mismatches")
    @Operation(summary = "List unresolved reconciliation mismatches")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Open mismatches returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not ADMIN or SUPPORT", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public List<ReconciliationMismatchResponse> listOpenMismatches() {
        return reconciliationMismatchRepository.findByResolutionStatus(MismatchResolutionStatus.OPEN).stream()
                .map(ReconciliationMismatchResponse::from)
                .toList();
    }

    @PostMapping("/mismatches/{id}/resolve")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Manually resolve a mismatch", description = "Records who resolved it and why. Does not itself alter the underlying settlement's ledger state.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mismatch resolved"),
            @ApiResponse(responseCode = "400", description = "Missing resolution reason", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not ADMIN or SUPPORT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No mismatch with this id", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Mismatch already resolved", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ReconciliationMismatchResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveMismatchRequest request,
                                                   @AuthenticationPrincipal AccessTokenClaims claims) {
        ReconciliationMismatch mismatch = reconciliationMismatchRepository.findById(id)
                .orElseThrow(() -> new MismatchNotFoundException(id));
        mismatch.resolve(request.reason());
        reconciliationMismatchRepository.save(mismatch);

        outboxWriter.write("SETTLEMENT", mismatch.getSettlementId(), KafkaTopics.RECONCILIATION_RESOLVED,
                new ReconciliationResolvedEvent(mismatch.getSettlementId(), "Manually resolved: " + request.reason(),
                        Instant.now()));
        auditLogService.record(claims.userId(), "RESOLVE_RECONCILIATION_MISMATCH", "reconciliation_mismatches", id,
                AuditOutcome.SUCCESS);

        return ReconciliationMismatchResponse.from(mismatch);
    }

    @GetMapping("/ledger-mismatches")
    @Operation(summary = "List unresolved ledger consistency mismatches",
            description = "Accounts whose stored balance was found to disagree with the net of their ledger entries.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Open ledger mismatches returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not ADMIN or SUPPORT", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public List<LedgerMismatchResponse> listOpenLedgerMismatches() {
        return ledgerMismatchRepository.findByResolutionStatus(MismatchResolutionStatus.OPEN).stream()
                .map(LedgerMismatchResponse::from)
                .toList();
    }

    @PostMapping("/ledger-mismatches/{id}/resolve")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Manually resolve a ledger mismatch", description = "Records who resolved it and why. Does not itself alter the account's balance or ledger entries.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ledger mismatch resolved"),
            @ApiResponse(responseCode = "400", description = "Missing resolution reason", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not ADMIN or SUPPORT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No ledger mismatch with this id", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Ledger mismatch already resolved", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public LedgerMismatchResponse resolveLedgerMismatch(@PathVariable UUID id,
                                                        @Valid @RequestBody ResolveMismatchRequest request,
                                                        @AuthenticationPrincipal AccessTokenClaims claims) {
        LedgerMismatch mismatch = ledgerMismatchRepository.findById(id)
                .orElseThrow(() -> new MismatchNotFoundException(id));
        mismatch.resolve(request.reason());
        ledgerMismatchRepository.save(mismatch);

        auditLogService.record(claims.userId(), "RESOLVE_LEDGER_MISMATCH", "ledger_mismatches", id,
                AuditOutcome.SUCCESS);

        return LedgerMismatchResponse.from(mismatch);
    }
}
