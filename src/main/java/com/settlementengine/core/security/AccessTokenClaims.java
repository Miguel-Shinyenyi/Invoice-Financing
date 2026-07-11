package com.settlementengine.core.security;

import java.util.UUID;

public record AccessTokenClaims(UUID userId, String role, UUID ownerId) {
}
