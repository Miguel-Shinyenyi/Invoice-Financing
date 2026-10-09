package com.settlementengine.core.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementStatusTest {

    @ParameterizedTest
    @CsvSource({
            "PENDING, CONFIRMED, true",
            "PENDING, FAILED, true",
            "PENDING, UNKNOWN, true",
            "PENDING, REVERSED, false",
            "PENDING, PENDING, false",
            "UNKNOWN, CONFIRMED, true",
            "UNKNOWN, FAILED, true",
            "UNKNOWN, UNKNOWN, false",
            "UNKNOWN, REVERSED, false",
            "CONFIRMED, REVERSED, true",
            "CONFIRMED, PENDING, false",
            "CONFIRMED, CONFIRMED, false",
            "CONFIRMED, FAILED, false",
            "CONFIRMED, UNKNOWN, false",
            "FAILED, PENDING, false",
            "FAILED, CONFIRMED, false",
            "FAILED, UNKNOWN, false",
            "FAILED, REVERSED, false",
            "REVERSED, PENDING, false",
            "REVERSED, CONFIRMED, false",
            "REVERSED, FAILED, false",
            "REVERSED, UNKNOWN, false",
    })
    void enforcesStateMachineTransitionRules(SettlementStatus from, SettlementStatus to, boolean expected) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }
}
