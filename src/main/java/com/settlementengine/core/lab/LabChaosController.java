package com.settlementengine.core.lab;

import com.settlementengine.core.api.ReconciliationRunResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@LabController
@RequestMapping("/lab")
public class LabChaosController {

    private final LabChaosService chaos;

    public LabChaosController(LabChaosService chaos) {
        this.chaos = chaos;
    }

    public record HandEditRequest(BigDecimal delta) {
    }

    @PostMapping("/external/{ref}/forget")
    public Map<String, Object> forget(@PathVariable String ref) {
        return chaos.forget(ref);
    }

    @PostMapping("/external/{ref}/corrupt")
    public Map<String, Object> corrupt(@PathVariable String ref, @RequestBody LabChaosService.CorruptRequest body) {
        return chaos.corrupt(ref, body);
    }

    @PostMapping("/accounts/{id}/hand-edit-balance")
    public Map<String, Object> handEdit(@PathVariable UUID id, @RequestBody HandEditRequest body) {
        return chaos.handEditBalance(id, body == null ? null : body.delta());
    }

    @PostMapping("/accounts/{id}/repair-balance")
    public Map<String, Object> repair(@PathVariable UUID id) {
        return chaos.repairBalance(id);
    }

    @PostMapping("/reconciliation/run")
    public ReconciliationRunResponse runReconciliation() {
        return ReconciliationRunResponse.from(chaos.runReconciliation());
    }

    @PostMapping("/sweep/run")
    public Map<String, String> runSweep() {
        chaos.runSweep();
        return Map.of("status", "completed");
    }
}
