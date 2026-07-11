package com.settlementengine.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenTest {

    @Test
    void activeWhenNotExpiredAndNotRevoked() {
        RefreshToken token = new RefreshToken(UUID.randomUUID(), UUID.randomUUID(), "hash",
                Instant.now().plus(1, ChronoUnit.DAYS));

        assertThat(token.isActive(Instant.now())).isTrue();
    }

    @Test
    void inactiveWhenExpired() {
        RefreshToken token = new RefreshToken(UUID.randomUUID(), UUID.randomUUID(), "hash",
                Instant.now().minus(1, ChronoUnit.SECONDS));

        assertThat(token.isActive(Instant.now())).isFalse();
    }

    @Test
    void inactiveWhenRevoked() {
        RefreshToken token = new RefreshToken(UUID.randomUUID(), UUID.randomUUID(), "hash",
                Instant.now().plus(1, ChronoUnit.DAYS));

        token.revoke();

        assertThat(token.isActive(Instant.now())).isFalse();
        assertThat(token.getRevokedAt()).isNotNull();
    }
}
