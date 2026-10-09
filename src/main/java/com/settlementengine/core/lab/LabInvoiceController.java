package com.settlementengine.core.lab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@LabController
@RequestMapping("/lab/invoices")
public class LabInvoiceController {

    private final LabInvoiceService invoices;

    public LabInvoiceController(LabInvoiceService invoices) {
        this.invoices = invoices;
    }

    public record DemoRequest(String scenario) {
    }

    public record MarkPaidRequest(BigDecimal amount) {
    }

    @GetMapping("/scenarios")
    public List<Map<String, String>> scenarios() {
        return invoices.catalog();
    }

    @PostMapping("/demo")
    public LabInvoiceService.ScenarioResult demo(@RequestBody DemoRequest request) {
        return invoices.runScenario(request == null ? null : request.scenario());
    }

    @PostMapping("/{id}/mark-paid")
    public Map<String, Object> markPaid(@PathVariable UUID id, @RequestBody(required = false) MarkPaidRequest request) {
        return invoices.markPaid(id, request == null ? null : request.amount());
    }

    @PostMapping("/repayment-run")
    public Map<String, Object> repaymentRun() {
        return invoices.repaymentRun();
    }
}
