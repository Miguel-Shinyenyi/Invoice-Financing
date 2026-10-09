package com.settlementengine.core.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerAccountTest {

    private LedgerAccount account(String balance) {
        return new LedgerAccount(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(balance), "USD");
    }

    @Test
    void creditIncreasesBalance() {
        LedgerAccount account = account("100.00");

        account.credit(new BigDecimal("50.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("150.00");
    }

    @Test
    void debitDecreasesBalanceWhenSufficientFunds() {
        LedgerAccount account = account("100.00");

        account.debit(new BigDecimal("40.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void debitRejectsAmountExceedingBalance() {
        LedgerAccount account = account("30.00");

        assertThatThrownBy(() -> account.debit(new BigDecimal("30.01")))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("30.00");
    }
}
