package com.settlementengine.core.lab;

import com.settlementengine.core.gateway.ExternalSettlementGateway;
import com.settlementengine.core.gateway.GatewayResult;
import com.settlementengine.core.gateway.MockExternalSystem;
import com.settlementengine.core.gateway.SettlementExecutionRequest;
import com.settlementengine.core.gateway.SettlementOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;

import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Decorates the mock external system at the gateway boundary only. Faults are never injected
 * inside the engine's own logic: the engine sees exactly what it would see from a real, flaky
 * external system.
 */
@LabComponent
@Primary
public class FaultInjectingGateway implements ExternalSettlementGateway {

    private static final Logger log = LoggerFactory.getLogger(FaultInjectingGateway.class);

    private final MockExternalSystem delegate;
    private final LabOrphanedExternalRecords orphans;
    private final RandomGenerator rng;

    @Autowired
    public FaultInjectingGateway(MockExternalSystem delegate, LabOrphanedExternalRecords orphans) {
        this(delegate, orphans, new Random());
    }

    FaultInjectingGateway(MockExternalSystem delegate, LabOrphanedExternalRecords orphans, RandomGenerator rng) {
        this.delegate = delegate;
        this.orphans = orphans;
        this.rng = rng;
    }

    @Override
    public GatewayResult execute(SettlementExecutionRequest request) {
        LabFaultPlan plan = LabFaultContext.get();
        LabFault fault = plan == null ? LabFault.NONE : plan.resolve(rng);
        switch (fault) {
            case REQUEST_LOST:
                log.info("Gateway call: fault=REQUEST_LOST, request never reached the external system (threw before any record)");
                throw new LabInjectedFaultException(fault);
            case RESPONSE_LOST: {
                GatewayResult real = delegate.execute(request);
                orphans.add(request.settlementId(), real.externalRef());
                log.info("Gateway call: fault=RESPONSE_LOST, external system wrote {} then the response was lost", real.externalRef());
                throw new LabInjectedFaultException(fault);
            }
            case DECLINED:
                log.info("Gateway call: fault=DECLINED, external system answered FAILED");
                return new GatewayResult(SettlementOutcome.FAILED, null);
            case SLOW: {
                sleep(plan.slowMs());
                GatewayResult real = delegate.execute(request);
                log.info("Gateway call: fault=SLOW (+{} ms), outcome={} ref={}", plan.slowMs(), real.outcome(), real.externalRef());
                return real;
            }
            default: {
                GatewayResult real = delegate.execute(request);
                log.info("Gateway call: fault=NONE, outcome={} ref={}", real.outcome(), real.externalRef());
                return real;
            }
        }
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
