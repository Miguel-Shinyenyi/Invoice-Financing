package com.settlementengine.core.reconciliation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StalePendingSettlementSweepScheduler {

    private final StalePendingSettlementSweepService stalePendingSettlementSweepService;

    public StalePendingSettlementSweepScheduler(StalePendingSettlementSweepService stalePendingSettlementSweepService) {
        this.stalePendingSettlementSweepService = stalePendingSettlementSweepService;
    }

    @Scheduled(fixedDelayString = "${settlement-engine.reconciliation.scheduled-fixed-delay-ms:60000}")
    public void runScheduled() {
        stalePendingSettlementSweepService.runOnce();
    }
}
