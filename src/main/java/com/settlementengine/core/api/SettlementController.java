package com.settlementengine.core.api;

import com.settlementengine.core.domain.AuditOutcome;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.domain.SettlementNotFoundException;
import com.settlementengine.core.readmodel.SettlementReadModel;
import com.settlementengine.core.readmodel.SettlementReadModelRepository;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.repository.SettlementRepository;
import com.settlementengine.core.security.AccessTokenClaims;
import com.settlementengine.core.security.AuditLogService;
import com.settlementengine.core.security.RowLevelAccessGuard;
import com.settlementengine.core.service.CreateSettlementCommand;
import com.settlementengine.core.service.SettlementResult;
import com.settlementengine.core.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/settlements")
@Tag(name = "Settlements", description = "Create and inspect idempotent settlements")
public class SettlementController {

    private final SettlementService settlementService;
    private final SettlementRepository settlementRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final SettlementReadModelRepository settlementReadModelRepository;
    private final RowLevelAccessGuard rowLevelAccessGuard;
    private final AuditLogService auditLogService;

    public SettlementController(SettlementService settlementService, SettlementRepository settlementRepository,
                                 LedgerAccountRepository ledgerAccountRepository,
                                 SettlementReadModelRepository settlementReadModelRepository,
                                 RowLevelAccessGuard rowLevelAccessGuard,
                                 AuditLogService auditLogService) {
        this.settlementService = settlementService;
        this.settlementRepository = settlementRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.settlementReadModelRepository = settlementReadModelRepository;
        this.rowLevelAccessGuard = rowLevelAccessGuard;
        this.auditLogService = auditLogService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
    @Operation(summary = "Create a settlement",
            description = "Moves funds between two ledger accounts. Requires an Idempotency-Key header (UUID); "
                    + "retrying the same key with the same body returns the original result instead of "
                    + "reprocessing. Retrying with the same key and a different body is rejected as a conflict.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Settlement processed (status may be CONFIRMED, FAILED, or UNKNOWN)"),
            @ApiResponse(responseCode = "400", description = "Missing/invalid Idempotency-Key header or invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not ADMIN or SUPPORT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Source or destination account does not exist", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Idempotency key is mid-flight, or was reused with a different request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Currency mismatch, self-settlement, or insufficient balance", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public SettlementResponse create(
            @Parameter(description = "Client-generated UUID identifying this logical request; reuse it exactly to safely retry", required = true, example = "3f5b8c2e-6b0a-4e9b-9d1a-8f2f7a6b1c33")
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CreateSettlementRequest request,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        CreateSettlementCommand command = new CreateSettlementCommand(
                request.sourceAccountId(), request.destinationAccountId(), request.amount(), request.currency());
        SettlementResult result = settlementService.createSettlement(idempotencyKey, command);
        auditLogService.record(claims.userId(), "CREATE_SETTLEMENT", "settlements", result.settlementId(), AuditOutcome.SUCCESS);
        return SettlementResponse.from(result);
    }

    @GetMapping
    @Operation(summary = "List settlements",
            description = "Paginated, optionally filtered by status. Reads from the CQRS read model, not the "
                    + "write-side settlements table. READ_ONLY users only see settlements touching an account they own.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of settlements"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public Page<SettlementSummaryResponse> list(
            @Parameter(description = "Optional status filter, e.g. CONFIRMED") @RequestParam(required = false) String status,
            Pageable pageable,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        UUID ownerFilter = "READ_ONLY".equals(claims.role()) ? claims.ownerId() : null;
        Page<SettlementReadModel> page = settlementReadModelRepository.findVisible(ownerFilter, status, pageable);
        auditLogService.record(claims.userId(), "LIST_SETTLEMENTS", "settlement_read_model", null, AuditOutcome.SUCCESS);
        return page.map(SettlementSummaryResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a settlement by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settlement found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "READ_ONLY user does not own either side of this settlement", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No settlement with this id", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public SettlementResponse get(@PathVariable UUID id, @AuthenticationPrincipal AccessTokenClaims claims) {
        Settlement settlement = settlementRepository.findById(id)
                .orElseThrow(() -> new SettlementNotFoundException(id));
        LedgerAccount source = ledgerAccountRepository.findById(settlement.getSourceAccountId())
                .orElseThrow(() -> new IllegalStateException("Source account missing for settlement " + id));
        LedgerAccount destination = ledgerAccountRepository.findById(settlement.getDestinationAccountId())
                .orElseThrow(() -> new IllegalStateException("Destination account missing for settlement " + id));
        try {
            rowLevelAccessGuard.requireOwnership(claims, source.getOwnerId(), destination.getOwnerId());
        } catch (AccessDeniedException denied) {
            auditLogService.record(claims.userId(), "GET_SETTLEMENT", "settlements", id, AuditOutcome.DENIED);
            throw denied;
        }
        auditLogService.record(claims.userId(), "GET_SETTLEMENT", "settlements", id, AuditOutcome.SUCCESS);
        return SettlementResponse.from(settlement);
    }
}
