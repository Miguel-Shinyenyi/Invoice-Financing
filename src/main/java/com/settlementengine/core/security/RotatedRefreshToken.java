package com.settlementengine.core.security;

import java.util.UUID;

public record RotatedRefreshToken(UUID userId, String rawToken) {
}
