package com.settlementengine.core.lab;

import com.settlementengine.core.invoicing.Advance;
import com.settlementengine.core.invoicing.CreateInvoiceCommand;
import com.settlementengine.core.invoicing.FraudAssessment;
import com.settlementengine.core.invoicing.Invoice;
import com.settlementengine.core.invoicing.InvoiceNotFoundException;
import com.settlementengine.core.invoicing.InvoiceRepaymentService;
import com.settlementengine.core.invoicing.InvoiceService;
import com.settlementengine.core.invoicing.InvoiceStatus;
import com.settlementengine.core.invoicing.MockInvoicePaymentSource;
import com.settlementengine.core.repository.FraudAssessmentRepository;
import com.settlementengine.core.repository.InvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Invoice + fraud scenarios, run through the REAL InvoiceService (and, through it, the real ml-service). */
@LabComponent
public class LabInvoiceService {

    public record Assessment(BigDecimal score, String decision, List<String> reasons) {
    }

    public record ScenarioResult(String scenario, String title, String description, UUID invoiceId, UUID accountId,
                                 int httpStatus, String error, Assessment assessment, Map<String, Object> inputsSent,
                                 Map<String, Object> advance, String invoiceStatus, List<String> setup, String requestId) {
    }

    private static final Logger log = LoggerFactory.getLogger(LabInvoiceService.class);

    static final BigDecimal OPENING_BALANCE = new BigDecimal("100.00");

    private final InvoiceService invoiceService;
    private final InvoiceRepository invoiceRepository;
    private final FraudAssessmentRepository fraudAssessmentRepository;
    private final InvoiceRepaymentService repaymentService;
    private final MockInvoicePaymentSource paymentSource;
    private final JdbcTemplate jdbc;

    public LabInvoiceService(InvoiceService invoiceService, InvoiceRepository invoiceRepository,
                             FraudAssessmentRepository fraudAssessmentRepository,
                             InvoiceRepaymentService repaymentService, MockInvoicePaymentSource paymentSource,
                             JdbcTemplate jdbc) {
        this.invoiceService = invoiceService;
        this.invoiceRepository = invoiceRepository;
        this.fraudAssessmentRepository = fraudAssessmentRepository;
        this.repaymentService = repaymentService;
        this.paymentSource = paymentSource;
        this.jdbc = jdbc;
    }

    public List<Map<String, String>> catalog() {
        return Arrays.stream(LabInvoiceScenario.values())
                .map(s -> Map.of("id", s.name(), "title", s.title(), "description", s.description())).toList();
    }

    public ScenarioResult runScenario(String name) {
        LabInvoiceScenario scenario;
        try {
            scenario = LabInvoiceScenario.valueOf(name == null ? "" : name.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new LabValidationException("scenario must be one of " + Arrays.toString(LabInvoiceScenario.values()));
        }
        String tag = UUID.randomUUID().toString().substring(0, 8);
        List<String> setup = new ArrayList<>();
        UUID account;
        Invoice invoice;
        switch (scenario) {
            case DUPLICATE_REFERENCE -> {
                String ref = "LAB-DUP-" + tag;
                UUID other = newBusinessAccount(30);
                invoiceService.submitInvoice(command(other, ref, "300.00"));
                setup.add("Another business already holds an invoice with customer reference " + ref);
                account = newBusinessAccount(30);
                invoice = invoiceService.submitInvoice(command(account, ref, "400.00"));
            }
            case NEW_ACCOUNT_HIGH_ADVANCE -> {
                account = newBusinessAccount(0);
                setup.add("Created a business account aged 0 days");
                invoice = invoiceService.submitInvoice(command(account, "LAB-NEW-" + tag, "1000.00"));
            }
            case RAPID_REFINANCING -> {
                account = newBusinessAccount(30);
                for (int i = 1; i <= 3; i++) {
                    Invoice prior = invoiceService.submitInvoice(command(account, "LAB-PRIOR-" + tag + "-" + i, "100.00"));
                    invoiceService.financeInvoice(prior.getId(), UUID.randomUUID());
                    setup.add("Financed prior invoice " + i + " of 3 (" + prior.getId() + ")");
                }
                invoice = invoiceService.submitInvoice(command(account, "LAB-REFI-" + tag, "100.00"));
            }
            case BLOCKED_COMBINATION -> {
                String ref = "LAB-BLOCK-" + tag;
                UUID other = newBusinessAccount(30);
                invoiceService.submitInvoice(command(other, ref, "300.00"));
                setup.add("Another business already holds an invoice with customer reference " + ref);
                account = newBusinessAccount(0);
                setup.add("Created a business account aged 0 days");
                invoice = invoiceService.submitInvoice(command(account, ref, "1000.00"));
            }
            default -> {
                account = newBusinessAccount(30);
                invoice = invoiceService.submitInvoice(command(account, "LAB-CLEAN-" + tag, "400.00"));
            }
        }

        Map<String, Object> inputs = inputsFor(invoice);
        int status = HttpStatus.OK.value();
        String error = null;
        Advance advance = null;
        // One line carrying the invoice id (and, under the OpenTelemetry agent, the trace id), so traces can be found by invoice.
        MDC.put("invoiceId", invoice.getId().toString());
        try {
            log.info("Lab invoice scenario {}: financing invoice {} (inputs {})", scenario, invoice.getId(), inputs);
        } finally {
            MDC.remove("invoiceId");
        }
        try {
            advance = invoiceService.financeInvoice(invoice.getId(), UUID.randomUUID());
        } catch (RuntimeException ex) {
            HttpStatus mapped = LabHttpStatus.statusFor(ex);
            if (mapped == null) {
                throw ex;
            }
            status = mapped.value();
            error = ex.getMessage();
        }
        Invoice after = invoiceRepository.findById(invoice.getId()).orElseThrow();
        return new ScenarioResult(scenario.name(), scenario.title(), scenario.description(), invoice.getId(), account,
                status, error, latestAssessment(invoice.getId()), inputs, advance == null ? null : advanceView(advance),
                after.getStatus().name(), setup, MDC.get("requestId"));
    }

    public Map<String, Object> markPaid(UUID invoiceId, BigDecimal amount) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        BigDecimal paid = amount == null ? invoice.getAmount() : amount;
        if (paid.signum() <= 0 || paid.stripTrailingZeros().scale() > 2 || paid.compareTo(new BigDecimal("1000000")) > 0) {
            throw new LabValidationException("amount must be positive, at most 2 decimals, at most 1000000");
        }
        paymentSource.markPaid(invoice.getExternalSourceRef(), paid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invoiceId", invoiceId);
        out.put("externalSourceRef", invoice.getExternalSourceRef());
        out.put("paidAmount", paid);
        out.put("invoiceAmount", invoice.getAmount());
        out.put("willRepay", invoice.getStatus() != InvoiceStatus.ISSUED && paid.compareTo(invoice.getAmount()) == 0);
        out.put("note", paid.compareTo(invoice.getAmount()) == 0
                ? "The next repayment run collects advance + fee from the business to the platform."
                : "Amount differs from the invoice: the repayment run logs a warning and leaves it for review.");
        return out;
    }

    public Map<String, Object> repaymentRun() {
        repaymentService.checkRepayments();
        return Map.of("status", "completed");
    }

    private Map<String, Object> inputsFor(Invoice invoice) {
        Map<String, Object> in = new LinkedHashMap<>();
        Instant created = jdbc.queryForObject("select created_at from ledger_accounts where id = ?",
                java.sql.Timestamp.class, invoice.getBusinessAccountId()).toInstant();
        in.put("business_account_age_days", Duration.between(created, Instant.now()).toDays());
        in.put("duplicate_customer_reference_count", invoiceRepository
                .countByCustomerReferenceAndBusinessAccountIdNot(invoice.getCustomerReference(), invoice.getBusinessAccountId()));
        in.put("outstanding_advance_count", invoiceRepository.countByBusinessAccountIdAndStatusIn(
                invoice.getBusinessAccountId(), List.of(InvoiceStatus.FINANCED, InvoiceStatus.OVERDUE)));
        in.put("requested_advance_amount", invoice.getAmount().multiply(new BigDecimal("0.80")).setScale(2, java.math.RoundingMode.HALF_UP));
        return in;
    }

    private Assessment latestAssessment(UUID invoiceId) {
        List<FraudAssessment> all = fraudAssessmentRepository.findByInvoiceId(invoiceId);
        if (all.isEmpty()) {
            return null;
        }
        FraudAssessment a = all.get(all.size() - 1);
        List<String> reasons = a.getReasons() == null || a.getReasons().isBlank() ? List.of()
                : Arrays.stream(a.getReasons().split(",\\s*")).toList();
        return new Assessment(a.getScore(), a.getDecision().name(), reasons);
    }

    private Map<String, Object> advanceView(Advance advance) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", advance.getId());
        out.put("amountAdvanced", advance.getAmountAdvanced());
        out.put("fee", advance.getFee());
        out.put("status", advance.getStatus().name());
        out.put("disbursedSettlementId", advance.getDisbursedSettlementId());
        return out;
    }

    private UUID newBusinessAccount(int ageDays) {
        UUID id = UUID.randomUUID();
        // A small opening balance (with its OPENING entry, so the ledger check passes): repayment collects
        // advance + fee, but the business only ever received the advance, so with 0 it could never repay.
        jdbc.update("insert into ledger_accounts (id, owner_id, balance, currency, version, created_at) "
                + "values (?, ?, ?, 'USD', 0, now() - make_interval(days => ?))", id, UUID.randomUUID(), OPENING_BALANCE, ageDays);
        jdbc.update("insert into ledger_entries (id, settlement_id, account_id, entry_type, amount, created_at) "
                + "values (?, null, ?, 'OPENING', ?, now())", UUID.randomUUID(), id, OPENING_BALANCE);
        return id;
    }

    private static CreateInvoiceCommand command(UUID account, String reference, String amount) {
        return new CreateInvoiceCommand(account, reference, new BigDecimal(amount), "USD",
                Instant.now().plus(Duration.ofDays(30)));
    }
}
