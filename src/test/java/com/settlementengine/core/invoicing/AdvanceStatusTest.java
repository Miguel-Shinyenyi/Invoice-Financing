package com.settlementengine.core.invoicing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AdvanceStatusTest {

    @ParameterizedTest
    @CsvSource({
            "DISBURSED, REPAID, true",
            "DISBURSED, DEFAULTED, true",
            "DISBURSED, DISBURSED, false",
            "REPAID, DISBURSED, false",
            "REPAID, DEFAULTED, false",
            "REPAID, REPAID, false",
            "DEFAULTED, DISBURSED, false",
            "DEFAULTED, REPAID, false",
            "DEFAULTED, DEFAULTED, false",
    })
    void enforcesStateMachineTransitionRules(AdvanceStatus from, AdvanceStatus to, boolean expected) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }
}
