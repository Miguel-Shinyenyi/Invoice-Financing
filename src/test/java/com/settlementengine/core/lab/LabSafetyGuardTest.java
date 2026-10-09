package com.settlementengine.core.lab;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabSafetyGuardTest {

    private static final List<String> FORBIDDEN = List.of("107.155.122.29");

    @Test
    void acceptsADatabaseNamedWithTheLabSuffix() {
        LabSafetyGuard.verify("jdbc:postgresql://localhost:5432/settlement_engine_lab", FORBIDDEN);
    }

    @Test
    void refusesADatabaseWithoutTheLabSuffix() {
        assertThatThrownBy(() -> LabSafetyGuard.verify("jdbc:postgresql://localhost:5432/settlement_engine", FORBIDDEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("_lab");
    }

    @Test
    void refusesTheStagingHostEvenWhenTheDatabaseNameLooksLikeALabOne() {
        assertThatThrownBy(() -> LabSafetyGuard.verify("jdbc:postgresql://107.155.122.29:5432/x_lab", FORBIDDEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("107.155.122.29");
    }

    @Test
    void ignoresQueryParametersWhenReadingTheDatabaseName() {
        LabSafetyGuard.verify("jdbc:postgresql://db:5432/settlement_engine_lab?sslmode=disable", FORBIDDEN);
        assertThat(LabSafetyGuard.databaseName("jdbc:postgresql://db:5432/a_lab?x=1")).isEqualTo("a_lab");
    }

    @Test
    void refusesAnUnparseableUrl() {
        assertThatThrownBy(() -> LabSafetyGuard.verify("not-a-url", FORBIDDEN))
                .isInstanceOf(IllegalStateException.class);
    }
}
