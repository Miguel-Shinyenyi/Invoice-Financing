package com.settlementengine.core.lab;

import com.settlementengine.core.domain.AccountNotFoundException;
import com.settlementengine.core.domain.CurrencyMismatchException;
import com.settlementengine.core.domain.IdempotencyKeyReusedException;
import com.settlementengine.core.domain.InsufficientBalanceException;
import com.settlementengine.core.domain.SelfSettlementException;
import com.settlementengine.core.domain.SettlementInProgressException;
import com.settlementengine.core.invoicing.FraudBlockedException;
import com.settlementengine.core.invoicing.InvoiceNotFoundException;
import com.settlementengine.core.invoicing.InvoiceTransitionException;
import org.springframework.http.HttpStatus;

/**
 * The HTTP status the REAL endpoint would answer for a domain exception. Mirrors the mapping in
 * {@code GlobalExceptionHandler}; {@code LabHttpStatusTest} asserts the two agree, so they cannot drift.
 */
final class LabHttpStatus {

    private LabHttpStatus() {
    }

    static HttpStatus statusFor(RuntimeException ex) {
        if (ex instanceof AccountNotFoundException || ex instanceof InvoiceNotFoundException) {
            return HttpStatus.NOT_FOUND;
        }
        if (ex instanceof CurrencyMismatchException || ex instanceof SelfSettlementException
                || ex instanceof InsufficientBalanceException || ex instanceof FraudBlockedException) {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
        if (ex instanceof IdempotencyKeyReusedException || ex instanceof SettlementInProgressException
                || ex instanceof InvoiceTransitionException) {
            return HttpStatus.CONFLICT;
        }
        return null;
    }
}
