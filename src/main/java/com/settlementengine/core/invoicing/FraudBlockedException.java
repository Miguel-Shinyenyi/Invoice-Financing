package com.settlementengine.core.invoicing;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class FraudBlockedException extends RuntimeException {

    public FraudBlockedException(UUID invoiceId, BigDecimal score, List<String> reasons) {
        super("Financing blocked for invoice " + invoiceId + " (fraud score " + score + "): " + reasons);
    }
}
