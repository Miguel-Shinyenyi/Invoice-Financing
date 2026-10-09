package com.settlementengine.core.lab;

import com.settlementengine.core.gateway.GatewayResult;
import com.settlementengine.core.gateway.MockExternalSystem;
import com.settlementengine.core.gateway.SettlementExecutionRequest;
import com.settlementengine.core.gateway.SettlementOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultInjectingGatewayTest {

    private MockExternalSystem external;
    private LabOrphanedExternalRecords orphans;
    private FaultInjectingGateway gateway;

    private final SettlementExecutionRequest request = new SettlementExecutionRequest(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "USD");

    @BeforeEach
    void setUp() {
        external = new MockExternalSystem();
        orphans = new LabOrphanedExternalRecords();
        gateway = new FaultInjectingGateway(external, orphans, new Random(1));
    }

    @AfterEach
    void clear() {
        LabFaultContext.clear();
    }

    @Test
    void noFaultDelegatesToTheRealMock() {
        GatewayResult result = gateway.execute(request);
        assertThat(result.outcome()).isEqualTo(SettlementOutcome.CONFIRMED);
        assertThat(external.findByReference(result.externalRef())).isPresent();
    }

    @Test
    void requestLostThrowsBeforeAnyExternalRecordExists() {
        LabFaultContext.set(LabFaultPlan.of(LabFault.REQUEST_LOST));
        assertThatThrownBy(() -> gateway.execute(request)).isInstanceOf(LabInjectedFaultException.class);
        assertThat(orphans.all()).isEmpty();
        assertThat(external.recordCount()).isZero();
    }

    @Test
    void responseLostWritesTheExternalRecordThenThrowsAndRemembersTheOrphan() {
        LabFaultContext.set(LabFaultPlan.of(LabFault.RESPONSE_LOST));
        assertThatThrownBy(() -> gateway.execute(request)).isInstanceOf(LabInjectedFaultException.class);
        assertThat(external.recordCount()).isEqualTo(1);
        assertThat(orphans.all()).hasSize(1);
        assertThat(orphans.all().get(0).settlementId()).isEqualTo(request.settlementId());
        assertThat(external.findByReference(orphans.all().get(0).externalRef())).isPresent();
    }

    @Test
    void declinedReturnsFailedWithoutAnExternalRecord() {
        LabFaultContext.set(LabFaultPlan.of(LabFault.DECLINED));
        GatewayResult result = gateway.execute(request);
        assertThat(result.outcome()).isEqualTo(SettlementOutcome.FAILED);
        assertThat(result.externalRef()).isNull();
        assertThat(external.recordCount()).isZero();
    }

    @Test
    void slowAddsLatencyButStillConfirms() {
        LabFaultContext.set(LabFaultPlan.slow(120));
        long start = System.nanoTime();
        GatewayResult result = gateway.execute(request);
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(110);
        assertThat(result.outcome()).isEqualTo(SettlementOutcome.CONFIRMED);
    }

    @Test
    void slowLatencyIsCappedAtThreeSeconds() {
        assertThat(LabFaultPlan.slow(60_000).slowMs()).isEqualTo(3000);
        assertThat(LabFaultPlan.slow(-5).slowMs()).isZero();
    }

    @Test
    void theFaultIsPerThreadSoConcurrentVisitorsNeverAffectEachOther() throws Exception {
        LabFaultContext.set(LabFaultPlan.of(LabFault.REQUEST_LOST));
        AtomicReference<GatewayResult> other = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        new Thread(() -> {
            other.set(gateway.execute(request));
            done.countDown();
        }).start();
        done.await();
        assertThat(other.get().outcome()).isEqualTo(SettlementOutcome.CONFIRMED);
        assertThatThrownBy(() -> gateway.execute(request)).isInstanceOf(LabInjectedFaultException.class);
    }

    @Test
    void aProbabilisticProfileFollowsItsWeightsDeterministicallyForAFixedSeed() {
        LabFaultContext.set(LabFaultPlan.profile(new LabFaultProfile(Map.of(LabFault.RESPONSE_LOST, 0.5), 0)));
        int thrown = 0;
        for (int i = 0; i < 200; i++) {
            try {
                gateway.execute(request);
            } catch (LabInjectedFaultException e) {
                thrown++;
            }
        }
        assertThat(thrown).isBetween(70, 130);
        assertThat(orphans.all()).hasSize(thrown);
    }

    @Test
    void probabilitiesAboveOneInTotalAreRejected() {
        assertThatThrownBy(() -> new LabFaultProfile(Map.of(LabFault.DECLINED, 0.7, LabFault.REQUEST_LOST, 0.7), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
