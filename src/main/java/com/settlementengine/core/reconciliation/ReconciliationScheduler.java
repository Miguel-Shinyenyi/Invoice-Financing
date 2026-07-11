package com.settlementengine.core.reconciliation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationScheduler {

    private final ReconciliationService reconciliationService;

    public ReconciliationScheduler(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(fixedDelayString = "${settlement-engine.reconciliation.scheduled-fixed-delay-ms:60000}")
    public void runScheduled() {
        reconciliationService.runOnce();
    }
}
