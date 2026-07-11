package com.settlementengine.core.api;

import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.domain.SettlementNotFoundException;
import com.settlementengine.core.repository.SettlementRepository;
import com.settlementengine.core.service.CreateSettlementCommand;
import com.settlementengine.core.service.SettlementResult;
import com.settlementengine.core.service.SettlementService;
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
public class SettlementController {

    private final SettlementService settlementService;
    private final SettlementRepository settlementRepository;

    public SettlementController(SettlementService settlementService, SettlementRepository settlementRepository) {
        this.settlementService = settlementService;
        this.settlementRepository = settlementRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementResponse create(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                      @Valid @RequestBody CreateSettlementRequest request) {
        CreateSettlementCommand command = new CreateSettlementCommand(
                request.sourceAccountId(), request.destinationAccountId(), request.amount(), request.currency());
        SettlementResult result = settlementService.createSettlement(idempotencyKey, command);
        return SettlementResponse.from(result);
    }

    @GetMapping("/{id}")
    public SettlementResponse get(@PathVariable UUID id) {
        Settlement settlement = settlementRepository.findById(id)
                .orElseThrow(() -> new SettlementNotFoundException(id));
        return SettlementResponse.from(settlement);
    }
}
