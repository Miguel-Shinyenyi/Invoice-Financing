package com.settlementengine.core.lab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.UUID;

@LabController
@RequestMapping("/lab/settlements")
public class LabSettlementController {

    private final LabPlaygroundService playground;
    private final LabInspector inspector;

    public LabSettlementController(LabPlaygroundService playground, LabInspector inspector) {
        this.playground = playground;
        this.inspector = inspector;
    }

    @PostMapping
    public PlaygroundResponse create(@RequestBody PlaygroundRequest request) {
        return playground.create(request);
    }

    /** Re-sends a key and body. Same handler as create; the key is mandatory here. */
    @PostMapping("/retry")
    public PlaygroundResponse retry(@RequestBody PlaygroundRequest request) {
        if (request == null || request.idempotencyKey() == null) {
            throw new LabValidationException("idempotencyKey is required to retry");
        }
        return playground.create(request);
    }

    @PostMapping("/orphan")
    public LabPlaygroundService.OrphanResponse orphan(@RequestBody PlaygroundRequest request) {
        return playground.orphan(request);
    }

    @GetMapping("/{id}/inspect")
    public LabInspection inspect(@PathVariable UUID id) {
        return inspector.inspect(id);
    }
}
