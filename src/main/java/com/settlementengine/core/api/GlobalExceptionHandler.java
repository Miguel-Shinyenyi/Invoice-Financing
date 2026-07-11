package com.settlementengine.core.api;

import com.settlementengine.core.domain.AccountNotFoundException;
import com.settlementengine.core.domain.CurrencyMismatchException;
import com.settlementengine.core.domain.IdempotencyKeyReusedException;
import com.settlementengine.core.domain.IllegalStateTransitionException;
import com.settlementengine.core.domain.InsufficientBalanceException;
import com.settlementengine.core.domain.SelfSettlementException;
import com.settlementengine.core.domain.SettlementInProgressException;
import com.settlementengine.core.domain.SettlementNotFoundException;
import com.settlementengine.core.invoicing.InvoiceNotFoundException;
import com.settlementengine.core.invoicing.InvoiceTransitionException;
import com.settlementengine.core.reconciliation.MismatchAlreadyResolvedException;
import com.settlementengine.core.reconciliation.MismatchNotFoundException;
import com.settlementengine.core.security.InvalidTokenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler({AccountNotFoundException.class, SettlementNotFoundException.class,
            MismatchNotFoundException.class, InvoiceNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(RuntimeException ex) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({CurrencyMismatchException.class, SelfSettlementException.class,
            InsufficientBalanceException.class})
    public ResponseEntity<ErrorResponse> handleUnprocessable(RuntimeException ex) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler({IdempotencyKeyReusedException.class, SettlementInProgressException.class,
            IllegalStateTransitionException.class, MismatchAlreadyResolvedException.class,
            InvoiceTransitionException.class})
    public ResponseEntity<ErrorResponse> handleConflict(RuntimeException ex) {
        return respond(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler({BadCredentialsException.class, InvalidTokenException.class})
    public ResponseEntity<ErrorResponse> handleAuthenticationFailure(RuntimeException ex) {
        return respond(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), status.getReasonPhrase(), message));
    }
}
