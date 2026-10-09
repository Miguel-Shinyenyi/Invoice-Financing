package com.settlementengine.core.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateSettlementRequest(
        @Schema(description = "Ledger account to debit", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID sourceAccountId,

        @Schema(description = "Ledger account to credit", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID destinationAccountId,

        @Schema(description = "Amount to move, must be positive", example = "100.00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,

        @Schema(description = "ISO 4217 currency code, must match both accounts' currency", example = "USD", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
}
