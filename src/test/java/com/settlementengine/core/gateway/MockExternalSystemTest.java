package com.settlementengine.core.gateway;

import com.settlementengine.core.reconciliation.ExternalRecord;
import com.settlementengine.core.reconciliation.ExternalStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockExternalSystemTest {

    private final MockExternalSystem system = new MockExternalSystem();

    private SettlementExecutionRequest request(BigDecimal amount) {
        return new SettlementExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                amount, "USD");
    }

    @Test
    void executeConfirmsAndRecordsAFindableExternalRecord() {
        GatewayResult result = system.execute(request(new BigDecimal("25.00")));

        assertThat(result.outcome()).isEqualTo(SettlementOutcome.CONFIRMED);
        assertThat(result.externalRef()).isNotBlank();

        ExternalRecord record = system.findByReference(result.externalRef()).orElseThrow();
        assertThat(record.amount()).isEqualByComparingTo("25.00");
        assertThat(record.currency()).isEqualTo("USD");
        assertThat(record.status()).isEqualTo(ExternalStatus.CONFIRMED);
    }

    @Test
    void unknownReferenceIsNotFound() {
        assertThat(system.findByReference("never-existed")).isEmpty();
    }

    @Test
    void forgetRemovesTheRecordSimulatingAMissingExternalRecord() {
        GatewayResult result = system.execute(request(new BigDecimal("10.00")));

        system.forget(result.externalRef());

        assertThat(system.findByReference(result.externalRef())).isEmpty();
    }

    @Test
    void corruptReplacesTheRecordSimulatingDisagreementWithTheExternalSystem() {
        GatewayResult result = system.execute(request(new BigDecimal("10.00")));

        system.corrupt(result.externalRef(), new ExternalRecord(result.externalRef(),
                new BigDecimal("999.00"), "USD", ExternalStatus.FAILED));

        ExternalRecord record = system.findByReference(result.externalRef()).orElseThrow();
        assertThat(record.amount()).isEqualByComparingTo("999.00");
        assertThat(record.status()).isEqualTo(ExternalStatus.FAILED);
    }
}
