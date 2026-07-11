package com.settlementengine.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowLevelAccessGuardTest {

    private final RowLevelAccessGuard guard = new RowLevelAccessGuard();

    @Test
    void adminIsNeverRestricted() {
        AccessTokenClaims admin = new AccessTokenClaims(UUID.randomUUID(), "ADMIN", null);

        assertThatCode(() -> guard.requireOwnership(admin, UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void supportIsNeverRestricted() {
        AccessTokenClaims support = new AccessTokenClaims(UUID.randomUUID(), "SUPPORT", null);

        assertThatCode(() -> guard.requireOwnership(support, UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void readOnlyAllowedWhenOwnerIdMatches() {
        UUID ownerId = UUID.randomUUID();
        AccessTokenClaims readOnly = new AccessTokenClaims(UUID.randomUUID(), "READ_ONLY", ownerId);

        assertThatCode(() -> guard.requireOwnership(readOnly, UUID.randomUUID(), ownerId))
                .doesNotThrowAnyException();
    }

    @Test
    void readOnlyDeniedWhenOwnerIdDoesNotMatchAnyResourceOwner() {
        AccessTokenClaims readOnly = new AccessTokenClaims(UUID.randomUUID(), "READ_ONLY", UUID.randomUUID());

        assertThatThrownBy(() -> guard.requireOwnership(readOnly, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void readOnlyDeniedWhenClaimsHaveNoOwnerId() {
        AccessTokenClaims readOnly = new AccessTokenClaims(UUID.randomUUID(), "READ_ONLY", null);

        assertThatThrownBy(() -> guard.requireOwnership(readOnly, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }
}
