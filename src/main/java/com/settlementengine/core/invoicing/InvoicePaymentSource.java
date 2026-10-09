package com.settlementengine.core.invoicing;

import java.math.BigDecimal;
import java.util.Optional;

public interface InvoicePaymentSource {

    Optional<BigDecimal> checkPayment(String externalSourceRef);
}
