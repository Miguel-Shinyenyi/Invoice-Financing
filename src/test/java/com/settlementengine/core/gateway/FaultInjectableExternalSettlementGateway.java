package com.settlementengine.core.gateway;

/**
 * Test-only decorator over a real {@link ExternalSettlementGateway} that can be told to throw or
 * add artificial latency on demand, simulating the network failures {@link SettlementService}
 * (a real production class, not mocked) has to cope with. Delegates to a real gateway for the
 * non-faulting case so CONFIRMED behavior stays realistic.
 */
public class FaultInjectableExternalSettlementGateway implements ExternalSettlementGateway {

    private final ExternalSettlementGateway delegate;
    private volatile boolean throwOnNextCall;
    private volatile long delayMillisPerCall;

    public FaultInjectableExternalSettlementGateway(ExternalSettlementGateway delegate) {
        this.delegate = delegate;
    }

    public void throwOnNextCall() {
        this.throwOnNextCall = true;
    }

    public void delayEveryCallBy(long millis) {
        this.delayMillisPerCall = millis;
    }

    @Override
    public GatewayResult execute(SettlementExecutionRequest request) {
        if (delayMillisPerCall > 0) {
            try {
                Thread.sleep(delayMillisPerCall);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (throwOnNextCall) {
            throwOnNextCall = false;
            throw new RuntimeException("simulated network timeout");
        }
        return delegate.execute(request);
    }
}
