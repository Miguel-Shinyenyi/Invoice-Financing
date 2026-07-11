package com.settlementengine.core.invoicing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceStatusTest {

    @ParameterizedTest
    @CsvSource({
            "ISSUED, FINANCED, true",
            "ISSUED, REPAID, false",
            "ISSUED, OVERDUE, false",
            "ISSUED, ISSUED, false",
            "FINANCED, REPAID, true",
            "FINANCED, OVERDUE, true",
            "FINANCED, ISSUED, false",
            "FINANCED, FINANCED, false",
            "OVERDUE, REPAID, true",
            "OVERDUE, FINANCED, false",
            "OVERDUE, ISSUED, false",
            "OVERDUE, OVERDUE, false",
            "REPAID, ISSUED, false",
            "REPAID, FINANCED, false",
            "REPAID, OVERDUE, false",
            "REPAID, REPAID, false",
    })
    void enforcesStateMachineTransitionRules(InvoiceStatus from, InvoiceStatus to, boolean expected) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }
}
