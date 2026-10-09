package com.settlementengine.core.lab;

import com.settlementengine.core.api.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Lab-specific exceptions plus a last-resort handler so no {@code /lab/**} response ever carries a
 * stack trace. Domain exceptions are still handled by {@code GlobalExceptionHandler}, which is
 * ordered ahead of this advice.
 */
@LabComponent
@RestControllerAdvice(annotations = LabController.class)
@Order(Ordered.LOWEST_PRECEDENCE)
public class LabExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LabExceptionHandler.class);

    @ExceptionHandler(LabValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(LabValidationException ex) {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(LabBusyException.class)
    public ResponseEntity<ErrorResponse> handleBusy(LabBusyException ex) {
        return respond(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    @ExceptionHandler(LabNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(LabNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return respond(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error in a lab endpoint", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error; details are in the server log.");
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(status.value(), status.getReasonPhrase(), message));
    }
}
