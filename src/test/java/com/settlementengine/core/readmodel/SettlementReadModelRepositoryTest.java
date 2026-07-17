package com.settlementengine.core.readmodel;

import com.settlementengine.core.AbstractIntegrationTest;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.repository.LedgerAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementReadModelRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private SettlementReadModelRepository settlementReadModelRepository;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    private LedgerAccount account(UUID ownerId) {
        return ledgerAccountRepository.save(
                new LedgerAccount(UUID.randomUUID(), ownerId, new BigDecimal("0.00"), "USD"));
    }

    private SettlementReadModel row(UUID sourceId, UUID destinationId, String status) {
        return settlementReadModelRepository.save(new SettlementReadModel(
                UUID.randomUUID(), sourceId, destinationId, new BigDecimal("10.00"), "USD", status, Instant.now()));
    }

    @Test
    void nullOwnerIdReturnsEverySettlementRegardlessOfOwnership() {
        LedgerAccount ownerA = account(UUID.randomUUID());
        LedgerAccount ownerB = account(UUID.randomUUID());
        row(ownerA.getId(), UUID.randomUUID(), "CONFIRMED");
        row(UUID.randomUUID(), ownerB.getId(), "CONFIRMED");

        var page = settlementReadModelRepository.findVisible(null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void ownerIdOnlyReturnsSettlementsTouchingAnAccountTheyOwn() {
        UUID targetOwner = UUID.randomUUID();
        LedgerAccount owned = account(targetOwner);
        LedgerAccount otherSource = account(UUID.randomUUID());
        LedgerAccount otherDestination = account(UUID.randomUUID());

        SettlementReadModel asSource = row(owned.getId(), otherDestination.getId(), "CONFIRMED");
        SettlementReadModel asDestination = row(otherSource.getId(), owned.getId(), "CONFIRMED");
        row(otherSource.getId(), otherDestination.getId(), "CONFIRMED");

        var page = settlementReadModelRepository.findVisible(targetOwner, null, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(SettlementReadModel::getSettlementId)
                .containsExactlyInAnyOrder(asSource.getSettlementId(), asDestination.getSettlementId());
    }

    @Test
    void statusFilterNarrowsResultsWhenProvided() {
        UUID ownerId = UUID.randomUUID();
        LedgerAccount owned = account(ownerId);
        LedgerAccount other = account(UUID.randomUUID());
        SettlementReadModel confirmed = row(owned.getId(), other.getId(), "CONFIRMED");
        row(owned.getId(), other.getId(), "FAILED");

        var page = settlementReadModelRepository.findVisible(null, "CONFIRMED", PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(SettlementReadModel::getSettlementId)
                .contains(confirmed.getSettlementId());
        assertThat(page.getContent())
                .extracting(SettlementReadModel::getStatus)
                .containsOnly("CONFIRMED");
    }

    @Test
    void resultsArePaginated() {
        UUID ownerId = UUID.randomUUID();
        LedgerAccount owned = account(ownerId);
        LedgerAccount other = account(UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            row(owned.getId(), other.getId(), "CONFIRMED");
        }

        Pageable firstPageOfTwo = PageRequest.of(0, 2);
        var page = settlementReadModelRepository.findVisible(ownerId, null, firstPageOfTwo);

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(3);
    }
}
