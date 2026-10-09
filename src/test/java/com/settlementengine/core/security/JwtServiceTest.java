package com.settlementengine.core.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-only-secret-key-at-least-32-bytes-long-0123456789";

    private JwtService jwtService(long ttlSeconds) {
        return new JwtService(SECRET, ttlSeconds);
    }

    @Test
    void issuedTokenParsesBackToSameUserIdAndRole() {
        JwtService jwtService = jwtService(900);
        UUID userId = UUID.randomUUID();

        String token = jwtService.issueAccessToken(userId, "ADMIN", null);
        AccessTokenClaims claims = jwtService.parse(token);

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.role()).isEqualTo("ADMIN");
        assertThat(claims.ownerId()).isNull();
    }

    @Test
    void issuedTokenCarriesOwnerIdWhenPresent() {
        JwtService jwtService = jwtService(900);
        UUID userId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        String token = jwtService.issueAccessToken(userId, "READ_ONLY", ownerId);
        AccessTokenClaims claims = jwtService.parse(token);

        assertThat(claims.ownerId()).isEqualTo(ownerId);
    }

    @Test
    void rejectsTokenSignedWithADifferentSecret() {
        JwtService issuer = new JwtService("a-completely-different-secret-key-0123456789-abcdefgh", 900);
        JwtService verifier = jwtService(900);
        String token = issuer.issueAccessToken(UUID.randomUUID(), "ADMIN", null);

        assertThatThrownBy(() -> verifier.parse(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsExpiredToken() throws InterruptedException {
        JwtService jwtService = jwtService(1);
        String token = jwtService.issueAccessToken(UUID.randomUUID(), "READ_ONLY", null);

        Thread.sleep(1100);

        assertThatThrownBy(() -> jwtService.parse(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsMalformedToken() {
        JwtService jwtService = jwtService(900);

        assertThatThrownBy(() -> jwtService.parse("not-a-real-token"))
                .isInstanceOf(InvalidTokenException.class);
    }
}
