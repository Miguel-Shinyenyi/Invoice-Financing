package com.settlementengine.core.invoicing;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stands in for checking the business's bank feed or accounting platform for an incoming customer
 * payment against a specific invoice. There is no natural "auto-confirm" moment here the way there
 * is for {@code MockExternalSystem}'s settlements — a customer pays whenever they pay — so
 * {@link #markPaid} is the only way to simulate that happening, for tests and demos.
 */
@Component
public class MockInvoicePaymentSource implements InvoicePaymentSource {

    private final Map<String, BigDecimal> payments = new ConcurrentHashMap<>();

    @Override
    public Optional<BigDecimal> checkPayment(String externalSourceRef) {
        return Optional.ofNullable(payments.get(externalSourceRef));
    }

    public void markPaid(String externalSourceRef, BigDecimal amount) {
        payments.put(externalSourceRef, amount);
    }
}
