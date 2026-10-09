package com.settlementengine.core.lab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/** Read-only views over the sandbox database. */
@LabController
@RequestMapping("/lab")
public class LabViewsController {

    private final LabDataService data;
    private final LabStateMachineService stateMachines;
    private final LabReconciliationViewService reconciliation;

    public LabViewsController(LabDataService data, LabStateMachineService stateMachines,
                              LabReconciliationViewService reconciliation) {
        this.data = data;
        this.stateMachines = stateMachines;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/data/{table}")
    public Map<String, Object> browse(@PathVariable String table, @RequestParam(required = false) String page,
                                      @RequestParam(required = false) String columns) {
        return data.browse(table, page, columns);
    }

    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                                     @RequestParam(required = false) String since, @RequestParam(defaultValue = "100") int limit) {
        return data.audit(actor, action, since, limit);
    }

    @GetMapping("/state-machines")
    public Map<String, Object> stateMachines() {
        return stateMachines.snapshot();
    }

    @GetMapping("/reconciliation/runs")
    public List<Map<String, Object>> runs(@RequestParam(defaultValue = "20") int limit) {
        return reconciliation.runs(limit);
    }

    @GetMapping("/reconciliation/mismatches")
    public List<Map<String, Object>> mismatches(@RequestParam(defaultValue = "OPEN") String status) {
        return reconciliation.mismatches(status);
    }

    @GetMapping("/reconciliation/ledger-mismatches")
    public List<Map<String, Object>> ledgerMismatches(@RequestParam(defaultValue = "OPEN") String status) {
        return reconciliation.ledgerMismatches(status);
    }

    @GetMapping("/reconciliation/stranded")
    public Map<String, Object> stranded() {
        return reconciliation.stranded();
    }

    @GetMapping("/reconciliation/summary")
    public Map<String, Object> summary() {
        return reconciliation.summary();
    }

    @PostMapping("/reconciliation/seen")
    public Map<String, String> seen() {
        reconciliation.markSeen();
        return Map.of("status", "ok");
    }
}
