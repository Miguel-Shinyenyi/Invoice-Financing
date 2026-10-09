package com.settlementengine.core.api;

import com.settlementengine.core.AbstractIntegrationTest;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.invoicing.Invoice;
import com.settlementengine.core.repository.InvoiceRepository;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class InvoiceApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private JwtService jwtService;

    private LedgerAccount business(UUID ownerId) {
        return ledgerAccountRepository.save(
                new LedgerAccount(UUID.randomUUID(), ownerId, new BigDecimal("0.00"), "USD"));
    }

    private Invoice invoice(UUID businessAccountId, String customerReference) {
        return invoiceRepository.save(new Invoice(UUID.randomUUID(), businessAccountId, customerReference,
                new BigDecimal("1000.00"), "USD", Instant.now().plus(30, ChronoUnit.DAYS), "INV-" + UUID.randomUUID()));
    }

    private String bearer(String role, UUID ownerId) {
        return "Bearer " + jwtService.issueAccessToken(UUID.randomUUID(), role, ownerId);
    }

    @Test
    void listInvoicesAsAdminReturnsEveryRowPaginated() throws Exception {
        LedgerAccount a = business(UUID.randomUUID());
        LedgerAccount b = business(UUID.randomUUID());
        invoice(a.getId(), "customer-a");
        invoice(b.getId(), "customer-b");

        mockMvc.perform(get("/invoices").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page.totalElements").isNumber());
    }

    @Test
    void listInvoicesAsReadOnlyOnlyReturnsRowsForAccountsTheyOwn() throws Exception {
        UUID targetOwner = UUID.randomUUID();
        LedgerAccount owned = business(targetOwner);
        LedgerAccount other = business(UUID.randomUUID());
        Invoice visible = invoice(owned.getId(), "customer-owned");
        invoice(other.getId(), "customer-other");

        mockMvc.perform(get("/invoices").header(HttpHeaders.AUTHORIZATION, bearer("READ_ONLY", targetOwner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(visible.getId().toString())))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void listInvoicesFiltersByStatus() throws Exception {
        LedgerAccount business = business(UUID.randomUUID());
        Invoice issued = invoice(business.getId(), "customer-issued");
        Invoice financed = invoice(business.getId(), "customer-financed");
        financed.transitionTo(com.settlementengine.core.invoicing.InvoiceStatus.FINANCED);
        invoiceRepository.save(financed);

        mockMvc.perform(get("/invoices").queryParam("status", "ISSUED")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(issued.getId().toString())))
                .andExpect(jsonPath("$.content[*].status", everyItem(is("ISSUED"))));
    }
}
