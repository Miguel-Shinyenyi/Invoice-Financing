package com.settlementengine.core.lab;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabGuardsTest {

    // ---- token bucket ----

    @Test
    void tokenBucketAllowsUpToTheLimitThenRefusesPerIp() {
        AtomicLong now = new AtomicLong(0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, Duration.ofMinutes(1), now::get);
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
        assertThat(limiter.tryAcquire("2.2.2.2")).as("another IP has its own bucket").isTrue();
    }

    @Test
    void tokenBucketRefillsOverTime() {
        AtomicLong now = new AtomicLong(0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, Duration.ofMinutes(1), now::get);
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a")).isFalse();
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
    }

    @Test
    void tokenBucketNeverExceedsItsCapacityAfterALongIdle() {
        AtomicLong now = new AtomicLong(0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, Duration.ofMinutes(1), now::get);
        now.addAndGet(Duration.ofHours(5).toNanos());
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
    }

    @Test
    void tokenBucketForgetsIdleKeysSoTheMapCannotGrowWithoutBound() {
        AtomicLong now = new AtomicLong(0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, Duration.ofMinutes(1), now::get);
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("ip-" + i);
        }
        now.addAndGet(Duration.ofMinutes(10).toNanos());
        limiter.tryAcquire("fresh");
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(2);
    }

    // ---- one run at a time + cooldown ----

    @Test
    void onlyOneRunAtATimeAndACooldownAfterwards() {
        AtomicLong now = new AtomicLong(0);
        LoadRunGate gate = new LoadRunGate(Duration.ofSeconds(10), now::get);
        assertThat(gate.tryStart("run-1")).isEqualTo(LoadRunGate.Decision.STARTED);
        assertThat(gate.tryStart("run-2")).isEqualTo(LoadRunGate.Decision.BUSY);
        gate.finish("run-1");
        assertThat(gate.tryStart("run-2")).isEqualTo(LoadRunGate.Decision.COOLING_DOWN);
        now.addAndGet(Duration.ofSeconds(11).toNanos());
        assertThat(gate.tryStart("run-2")).isEqualTo(LoadRunGate.Decision.STARTED);
    }

    @Test
    void finishingSomeOtherRunDoesNotReleaseTheGate() {
        AtomicLong now = new AtomicLong(0);
        LoadRunGate gate = new LoadRunGate(Duration.ZERO, now::get);
        gate.tryStart("run-1");
        gate.finish("not-the-active-run");
        assertThat(gate.tryStart("run-2")).isEqualTo(LoadRunGate.Decision.BUSY);
    }

    // ---- plan validation against server-side caps ----

    private final LabProperties props = LabPropertiesFixtures.defaults();

    @Test
    void aPlanInsideTheCapsIsAccepted() {
        LoadPlan plan = new LoadPlan(LoadScenario.FRESH_SETTLEMENTS, 5, 10, 500, new BigDecimal("1.00"), null, 42L);
        LoadPlanValidator.validate(plan, props.load());
    }

    @Test
    void tooManyVirtualUsersAreRefused() {
        LoadPlan plan = new LoadPlan(LoadScenario.FRESH_SETTLEMENTS, 21, 10, 500, new BigDecimal("1.00"), null, 1L);
        assertThatThrownBy(() -> LoadPlanValidator.validate(plan, props.load()))
                .isInstanceOf(LabValidationException.class).hasMessageContaining("virtualUsers");
    }

    @Test
    void tooLongARunIsRefused() {
        LoadPlan plan = new LoadPlan(LoadScenario.FRESH_SETTLEMENTS, 5, 31, 500, new BigDecimal("1.00"), null, 1L);
        assertThatThrownBy(() -> LoadPlanValidator.validate(plan, props.load()))
                .isInstanceOf(LabValidationException.class).hasMessageContaining("durationSeconds");
    }

    @Test
    void tooManyTotalRequestsAreRefused() {
        LoadPlan plan = new LoadPlan(LoadScenario.FRESH_SETTLEMENTS, 5, 10, 5001, new BigDecimal("1.00"), null, 1L);
        assertThatThrownBy(() -> LoadPlanValidator.validate(plan, props.load()))
                .isInstanceOf(LabValidationException.class).hasMessageContaining("totalRequests");
    }

    @Test
    void zeroOrNegativeValuesAreRefused() {
        assertThatThrownBy(() -> LoadPlanValidator.validate(
                new LoadPlan(LoadScenario.FRESH_SETTLEMENTS, 0, 10, 500, new BigDecimal("1.00"), null, 1L), props.load()))
                .isInstanceOf(LabValidationException.class);
        assertThatThrownBy(() -> LoadPlanValidator.validate(
                new LoadPlan(LoadScenario.FRESH_SETTLEMENTS, 5, -1, 500, new BigDecimal("1.00"), null, 1L), props.load()))
                .isInstanceOf(LabValidationException.class);
    }

    @Test
    void amountsOutsideTheFixedRangeAreRefused() {
        for (String bad : new String[] {"0", "-1", "100.01", "0.001"}) {
            LoadPlan plan = new LoadPlan(LoadScenario.FRESH_SETTLEMENTS, 5, 10, 500, new BigDecimal(bad), null, 1L);
            assertThatThrownBy(() -> LoadPlanValidator.validate(plan, props.load()))
                    .as("amount " + bad).isInstanceOf(LabValidationException.class);
        }
    }

    @Test
    void aMissingScenarioOrAmountIsRefused() {
        assertThatThrownBy(() -> LoadPlanValidator.validate(
                new LoadPlan(null, 5, 10, 500, new BigDecimal("1.00"), null, 1L), props.load()))
                .isInstanceOf(LabValidationException.class);
        assertThatThrownBy(() -> LoadPlanValidator.validate(
                new LoadPlan(LoadScenario.FRESH_SETTLEMENTS, 5, 10, 500, null, null, 1L), props.load()))
                .isInstanceOf(LabValidationException.class);
    }

    @Test
    void aFaultProfileIsLimitedToSumOfOneAndSlowCap() {
        LoadPlan plan = new LoadPlan(LoadScenario.FRESH_SETTLEMENTS, 5, 10, 500, new BigDecimal("1.00"),
                new LoadPlan.FaultSpec(0.05, 0.0, 0.0, 0.0, 0), 1L);
        LoadPlanValidator.validate(plan, props.load());
        LoadPlan bad = new LoadPlan(LoadScenario.FRESH_SETTLEMENTS, 5, 10, 500, new BigDecimal("1.00"),
                new LoadPlan.FaultSpec(0.6, 0.6, 0.0, 0.0, 0), 1L);
        assertThatThrownBy(() -> LoadPlanValidator.validate(bad, props.load()))
                .isInstanceOf(LabValidationException.class);
    }

    // ---- SSE caps ----

    @Test
    void sseSlotsAreCappedPerIpAndInTotalAndReleasable() {
        SseConnectionRegistry registry = new SseConnectionRegistry(2, 3);
        SseConnectionRegistry.Slot a1 = registry.tryAcquire("a").orElseThrow();
        SseConnectionRegistry.Slot a2 = registry.tryAcquire("a").orElseThrow();
        assertThat(registry.tryAcquire("a")).as("per-IP cap").isEmpty();
        registry.tryAcquire("b").orElseThrow();
        assertThat(registry.tryAcquire("c")).as("global cap").isEmpty();
        a1.close();
        a1.close(); // idempotent
        assertThat(registry.tryAcquire("c")).isPresent();
        assertThat(registry.active()).isEqualTo(3);
        a2.close();
    }
}
