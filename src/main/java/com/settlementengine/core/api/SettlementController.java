package com.settlementengine.core.api;

import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.domain.SettlementNotFoundException;
import com.settlementengine.core.repository.SettlementRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/settlements")
@Tag(name = "Settlements", description = "Create and inspect idempotent settlements")
public class SettlementController {

    private final SettlementService settlementService;
    private final SettlementRepository settlementRepository;

    public SettlementController(SettlementService settlementService, SettlementRepository settlementRepository) {
        this.settlementService = settlementService;
        this.settlementRepository = settlementRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a settlement",
            description = "Moves funds between two ledger accounts. Requires an Idempotency-Key header (UUID); "
                    + "retrying the same key with the same body returns the original result instead of "
                    + "reprocessing. Retrying with the same key and a different body is rejected as a conflict.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Settlement processed (status may be CONFIRMED, FAILED, or UNKNOWN)"),
            @ApiResponse(responseCode = "400", description = "Missing/invalid Idempotency-Key header or invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Source or destination account does not exist", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Idempotency key is mid-flight, or was reused with a different request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Currency mismatch, self-settlement, or insufficient balance", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public SettlementResponse create(
            @Parameter(description = "Client-generated UUID identifying this logical request; reuse it exactly to safely retry", required = true, example = "3f5b8c2e-6b0a-4e9b-9d1a-8f2f7a6b1c33")
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CreateSettlementRequest request) {
        CreateSettlementCommand command = new CreateSettlementCommand(
                request.sourceAccountId(), request.destinationAccountId(), request.amount(), request.currency());
        SettlementResult result = settlementService.createSettlement(idempotencyKey, command);
        return SettlementResponse.from(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a settlement by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settlement found"),
            @ApiResponse(responseCode = "404", description = "No settlement with this id", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public SettlementResponse get(@PathVariable UUID id) {
        Settlement settlement = settlementRepository.findById(id)
                .orElseThrow(() -> new SettlementNotFoundException(id));
        return SettlementResponse.from(settlement);
    }
}
