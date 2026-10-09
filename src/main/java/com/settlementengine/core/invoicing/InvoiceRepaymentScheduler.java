package com.settlementengine.core.invoicing;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InvoiceRepaymentScheduler {

    private final InvoiceRepaymentService invoiceRepaymentService;

    public InvoiceRepaymentScheduler(InvoiceRepaymentService invoiceRepaymentService) {
        this.invoiceRepaymentService = invoiceRepaymentService;
    }

    @Scheduled(fixedDelayString = "${settlement-engine.invoice-financing.scheduled-fixed-delay-ms:60000}")
    public void runScheduled() {
        invoiceRepaymentService.checkRepayments();
    }
}
