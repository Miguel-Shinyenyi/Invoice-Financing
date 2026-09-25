package com.settlementengine.core.reconciliation;

import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.domain.LedgerInconsistencyException;
import com.settlementengine.core.repository.LedgerEntryRepository;
import com.settlementengine.core.repository.LedgerMismatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerConsistencyServiceTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;
    @Mock
    private LedgerMismatchRepository ledgerMismatchRepository;

    private LedgerConsistencyService service;
    private LedgerAccount account;

    @BeforeEach
    void setUp() {
        service = new LedgerConsistencyService(ledgerEntryRepository, ledgerMismatchRepository);
        account = new LedgerAccount(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"), "USD");
    }

    @Test
    void matchingBalanceComparesByValueNotScale() {
        when(ledgerEntryRepository.sumNetByAccountId(account.getId())).thenReturn(new BigDecimal("100.0000"));

        service.verify(account);

        verify(ledgerMismatchRepository, never()).save(any());
    }

    @Test
    void mismatchRecordsAnOpenMismatchAndThrows() {
        when(ledgerEntryRepository.sumNetByAccountId(account.getId())).thenReturn(new BigDecimal("90.00"));
        when(ledgerMismatchRepository.findByAccountIdAndResolutionStatus(account.getId(), MismatchResolutionStatus.OPEN))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(account))
                .isInstanceOf(LedgerInconsistencyException.class)
                .hasMessageContaining(account.getId().toString())
                .hasMessageContaining("100.00")
                .hasMessageContaining("90.00");

        ArgumentCaptor<LedgerMismatch> captor = ArgumentCaptor.forClass(LedgerMismatch.class);
        verify(ledgerMismatchRepository).save(captor.capture());
        assertThat(captor.getValue().getAccountId()).isEqualTo(account.getId());
        assertThat(captor.getValue().getStoredBalance()).isEqualByComparingTo("100.00");
        assertThat(captor.getValue().getComputedBalance()).isEqualByComparingTo("90.00");
        assertThat(captor.getValue().getResolutionStatus()).isEqualTo(MismatchResolutionStatus.OPEN);
    }

    @Test
    void alreadyOpenMismatchIsNotDuplicatedButStillThrows() {
        when(ledgerEntryRepository.sumNetByAccountId(account.getId())).thenReturn(new BigDecimal("90.00"));
        when(ledgerMismatchRepository.findByAccountIdAndResolutionStatus(account.getId(), MismatchResolutionStatus.OPEN))
                .thenReturn(Optional.of(new LedgerMismatch(UUID.randomUUID(), account.getId(),
                        new BigDecimal("100.00"), new BigDecimal("90.00"), "earlier")));

        assertThatThrownBy(() -> service.verify(account)).isInstanceOf(LedgerInconsistencyException.class);

        verify(ledgerMismatchRepository, never()).save(any());
    }
}
