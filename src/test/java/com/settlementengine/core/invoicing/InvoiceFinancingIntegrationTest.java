package com.settlementengine.core.invoicing;

import com.settlementengine.core.AbstractIntegrationTest;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.repository.AdvanceRepository;
import com.settlementengine.core.repository.InvoiceRepository;
import com.settlementengine.core.repository.LedgerAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceFinancingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private InvoiceRepaymentService invoiceRepaymentService;
    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private AdvanceRepository advanceRepository;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private MockInvoicePaymentSource mockInvoicePaymentSource;
    @Value("${settlement-engine.invoice-financing.platform-account-id}")
    private UUID platformAccountId;

    private void ensurePlatformAccountHasBalance(String balance) {
        // Both test methods share this Spring context/database (see AbstractIntegrationTest and
        // testing.md), so the fixed platform-account-id can already exist from an earlier test.
        LedgerAccount platform = ledgerAccountRepository.findById(platformAccountId)
                .orElseGet(() -> new LedgerAccount(platformAccountId, UUID.randomUUID(), BigDecimal.ZERO, "USD"));
        platform.credit(new BigDecimal(balance).subtract(platform.getBalance()));
        ledgerAccountRepository.save(platform);
    }

    @Test
    void fullLifecycleSubmitFinanceDetectPaymentAndRepay() {
        ensurePlatformAccountHasBalance("100000.00");
        LedgerAccount business = ledgerAccountRepository.save(
                new LedgerAccount(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"), "USD"));

        CreateInvoiceCommand submitCommand = new CreateInvoiceCommand(business.getId(), "customer-1",
                new BigDecimal("1000.00"), "USD", Instant.now().plus(30, ChronoUnit.DAYS));
        Invoice invoice = invoiceService.submitInvoice(submitCommand);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);

        Advance advance = invoiceService.financeInvoice(invoice.getId(), UUID.randomUUID());
        assertThat(advance.getAmountAdvanced()).isEqualByComparingTo("800.00");
        assertThat(advance.getFee()).isEqualByComparingTo("20.00");
        assertThat(advance.getStatus()).isEqualTo(AdvanceStatus.DISBURSED);

        Invoice financedInvoice = invoiceRepository.findById(invoice.getId()).orElseThrow();
        assertThat(financedInvoice.getStatus()).isEqualTo(InvoiceStatus.FINANCED);

        LedgerAccount businessAfterDisbursement = ledgerAccountRepository.findById(business.getId()).orElseThrow();
        LedgerAccount platformAfterDisbursement = ledgerAccountRepository.findById(platformAccountId).orElseThrow();
        assertThat(businessAfterDisbursement.getBalance()).isEqualByComparingTo("900.00");
        assertThat(platformAfterDisbursement.getBalance()).isEqualByComparingTo("99200.00");

        mockInvoicePaymentSource.markPaid(invoice.getExternalSourceRef(), new BigDecimal("1000.00"));
        invoiceRepaymentService.checkRepayments();

        Invoice repaidInvoice = invoiceRepository.findById(invoice.getId()).orElseThrow();
        assertThat(repaidInvoice.getStatus()).isEqualTo(InvoiceStatus.REPAID);

        Advance repaidAdvance = advanceRepository.findByInvoiceId(invoice.getId()).orElseThrow();
        assertThat(repaidAdvance.getStatus()).isEqualTo(AdvanceStatus.REPAID);
        assertThat(repaidAdvance.getRepaidSettlementId()).isNotNull();

        LedgerAccount businessFinal = ledgerAccountRepository.findById(business.getId()).orElseThrow();
        LedgerAccount platformFinal = ledgerAccountRepository.findById(platformAccountId).orElseThrow();
        assertThat(businessFinal.getBalance()).isEqualByComparingTo("80.00");
        assertThat(platformFinal.getBalance()).isEqualByComparingTo("100020.00");
    }

    @Test
    void mismatchedPaymentDoesNotAutoResolveTheInvoice() {
        ensurePlatformAccountHasBalance("100000.00");
        LedgerAccount business = ledgerAccountRepository.save(
                new LedgerAccount(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"), "USD"));

        Invoice invoice = invoiceService.submitInvoice(new CreateInvoiceCommand(business.getId(), "customer-2",
                new BigDecimal("500.00"), "USD", Instant.now().plus(30, ChronoUnit.DAYS)));
        invoiceService.financeInvoice(invoice.getId(), UUID.randomUUID());

        mockInvoicePaymentSource.markPaid(invoice.getExternalSourceRef(), new BigDecimal("250.00"));
        invoiceRepaymentService.checkRepayments();

        Invoice stillFinanced = invoiceRepository.findById(invoice.getId()).orElseThrow();
        assertThat(stillFinanced.getStatus()).isEqualTo(InvoiceStatus.FINANCED);
    }
}
