package com.settlementengine.core.invoicing;

import com.settlementengine.core.repository.AdvanceRepository;
import com.settlementengine.core.repository.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class InvoiceTransactions {

    private final InvoiceRepository invoiceRepository;
    private final AdvanceRepository advanceRepository;

    public InvoiceTransactions(InvoiceRepository invoiceRepository, AdvanceRepository advanceRepository) {
        this.invoiceRepository = invoiceRepository;
        this.advanceRepository = advanceRepository;
    }

    @Transactional
    public Invoice claimForFinancing(UUID invoiceId) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        invoice.transitionTo(InvoiceStatus.FINANCED);
        invoiceRepository.save(invoice);
        return invoice;
    }

    @Transactional
    public Advance recordAdvance(UUID invoiceId, BigDecimal amountAdvanced, BigDecimal fee, UUID disbursedSettlementId) {
        Advance advance = new Advance(UUID.randomUUID(), invoiceId, amountAdvanced, fee, disbursedSettlementId);
        advanceRepository.save(advance);
        return advance;
    }

    @Transactional
    public void recordRepayment(UUID invoiceId, Advance advance, UUID repaidSettlementId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        invoice.transitionTo(InvoiceStatus.REPAID);
        invoiceRepository.save(invoice);

        advance.markRepaid(repaidSettlementId);
        advanceRepository.save(advance);
    }

    @Transactional
    public void markOverdue(UUID invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        invoice.transitionTo(InvoiceStatus.OVERDUE);
        invoiceRepository.save(invoice);
    }
}
