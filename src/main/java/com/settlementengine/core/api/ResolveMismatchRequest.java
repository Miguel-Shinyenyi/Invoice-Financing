package com.settlementengine.core.api;

import jakarta.validation.constraints.NotBlank;

public record ResolveMismatchRequest(@NotBlank String reason) {
}
