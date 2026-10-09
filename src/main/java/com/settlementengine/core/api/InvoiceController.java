package com.settlementengine.core.api;

import com.settlementengine.core.domain.AuditOutcome;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.invoicing.Advance;
import com.settlementengine.core.invoicing.CreateInvoiceCommand;
import com.settlementengine.core.invoicing.Invoice;
import com.settlementengine.core.invoicing.InvoiceNotFoundException;
import com.settlementengine.core.invoicing.InvoiceService;
import com.settlementengine.core.repository.AdvanceRepository;
import com.settlementengine.core.repository.InvoiceRepository;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.security.AccessTokenClaims;
import com.settlementengine.core.security.AuditLogService;
import com.settlementengine.core.security.RowLevelAccessGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.settlementengine.core.invoicing.InvoiceStatus;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/invoices")
@Tag(name = "Invoices", description = "Submit invoices for financing and track their advance/repayment status")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final InvoiceRepository invoiceRepository;
    private final AdvanceRepository advanceRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final RowLevelAccessGuard rowLevelAccessGuard;
    private final AuditLogService auditLogService;

    public InvoiceController(InvoiceService invoiceService, InvoiceRepository invoiceRepository,
                              AdvanceRepository advanceRepository, LedgerAccountRepository ledgerAccountRepository,
                              RowLevelAccessGuard rowLevelAccessGuard, AuditLogService auditLogService) {
        this.invoiceService = invoiceService;
        this.invoiceRepository = invoiceRepository;
        this.advanceRepository = advanceRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.rowLevelAccessGuard = rowLevelAccessGuard;
        this.auditLogService = auditLogService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
    @Operation(summary = "Submit an invoice for financing", description = "No money movement happens yet; the invoice starts ISSUED.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Invoice created"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not ADMIN or SUPPORT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Business account does not exist", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public InvoiceResponse submit(@Valid @RequestBody CreateInvoiceRequest request,
                                   @AuthenticationPrincipal AccessTokenClaims claims) {
        Invoice invoice = invoiceService.submitInvoice(new CreateInvoiceCommand(
                request.businessAccountId(), request.customerReference(), request.amount(),
                request.currency(), request.dueDate()));
        auditLogService.record(claims.userId(), "SUBMIT_INVOICE", "invoices", invoice.getId(), AuditOutcome.SUCCESS);
        return InvoiceResponse.from(invoice, null);
    }

    @PostMapping("/{id}/finance")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
    @Operation(summary = "Approve and disburse an advance against an invoice",
            description = "Requires an Idempotency-Key header. Disbursement goes through the same settlement engine as every other money movement in this system.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Advance disbursed"),
            @ApiResponse(responseCode = "400", description = "Missing/invalid Idempotency-Key header", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not ADMIN or SUPPORT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No invoice with this id", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Invoice is not ISSUED (already financed, repaid, or overdue)", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Platform account has insufficient balance to disburse", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public InvoiceResponse finance(@PathVariable UUID id,
                                    @Parameter(description = "Client-generated UUID identifying this logical request", required = true)
                                    @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                    @AuthenticationPrincipal AccessTokenClaims claims) {
        Advance advance = invoiceService.financeInvoice(id, idempotencyKey);
        Invoice invoice = invoiceRepository.findById(id).orElseThrow(() -> new InvoiceNotFoundException(id));
        auditLogService.record(claims.userId(), "FINANCE_INVOICE", "invoices", id, AuditOutcome.SUCCESS);
        return InvoiceResponse.from(invoice, advance);
    }

    @GetMapping
    @Operation(summary = "List invoices",
            description = "Paginated, optionally filtered by status. READ_ONLY users only see invoices for a "
                    + "business account they own.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of invoices"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public Page<InvoiceSummaryResponse> list(
            @Parameter(description = "Optional status filter, e.g. ISSUED") @RequestParam(required = false) InvoiceStatus status,
            Pageable pageable,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        UUID ownerFilter = "READ_ONLY".equals(claims.role()) ? claims.ownerId() : null;
        Page<Invoice> page = invoiceRepository.findVisible(ownerFilter, status, pageable);
        auditLogService.record(claims.userId(), "LIST_INVOICES", "invoices", null, AuditOutcome.SUCCESS);
        return page.map(InvoiceSummaryResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an invoice and its advance/repayment status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "READ_ONLY user does not own the invoice's business account", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No invoice with this id", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public InvoiceResponse get(@PathVariable UUID id, @AuthenticationPrincipal AccessTokenClaims claims) {
        Invoice invoice = invoiceRepository.findById(id).orElseThrow(() -> new InvoiceNotFoundException(id));
        LedgerAccount businessAccount = ledgerAccountRepository.findById(invoice.getBusinessAccountId())
                .orElseThrow(() -> new IllegalStateException("Business account missing for invoice " + id));

        try {
            rowLevelAccessGuard.requireOwnership(claims, businessAccount.getOwnerId());
        } catch (AccessDeniedException denied) {
            auditLogService.record(claims.userId(), "GET_INVOICE", "invoices", id, AuditOutcome.DENIED);
            throw denied;
        }
        auditLogService.record(claims.userId(), "GET_INVOICE", "invoices", id, AuditOutcome.SUCCESS);

        Advance advance = advanceRepository.findByInvoiceId(id).orElse(null);
        return InvoiceResponse.from(invoice, advance);
    }
}
