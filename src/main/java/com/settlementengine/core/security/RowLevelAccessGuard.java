package com.settlementengine.core.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.UUID;

@Component
public class RowLevelAccessGuard {

    public void requireOwnership(AccessTokenClaims claims, UUID... resourceOwnerIds) {
        if (!"READ_ONLY".equals(claims.role())) {
            return;
        }
        UUID ownerId = claims.ownerId();
        if (ownerId == null || Arrays.stream(resourceOwnerIds).noneMatch(ownerId::equals)) {
            throw new AccessDeniedException("Not permitted to access this resource");
        }
    }
}
