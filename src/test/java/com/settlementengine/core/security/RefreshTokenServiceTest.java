package com.settlementengine.core.security;

import com.settlementengine.core.domain.RefreshToken;
import com.settlementengine.core.repository.RefreshTokenRepository;
import com.settlementengine.core.util.Sha256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        refreshTokenService = new RefreshTokenService(refreshTokenRepository, 30);
    }

    @Test
    void issueCreatesActiveTokenWithMatchingHash() {
        UUID userId = UUID.randomUUID();

        String rawToken = refreshTokenService.issue(userId);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getTokenHash()).isEqualTo(Sha256.hex(rawToken));
        assertThat(saved.isActive(Instant.now())).isTrue();
    }

    @Test
    void validateAndRotateRevokesOldAndIssuesNewToken() {
        UUID userId = UUID.randomUUID();
        String rawToken = "raw-token-value";
        RefreshToken existing = new RefreshToken(UUID.randomUUID(), userId, Sha256.hex(rawToken),
                Instant.now().plus(1, ChronoUnit.DAYS));
        when(refreshTokenRepository.findByTokenHash(Sha256.hex(rawToken))).thenReturn(Optional.of(existing));

        RotatedRefreshToken rotated = refreshTokenService.validateAndRotate(rawToken);

        assertThat(rotated.userId()).isEqualTo(userId);
        assertThat(rotated.rawToken()).isNotBlank();
        assertThat(existing.getRevokedAt()).isNotNull();
        verify(refreshTokenRepository, times(2)).save(any());
    }

    @Test
    void validateAndRotateRejectsUnknownToken() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.validateAndRotate("unknown"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void validateAndRotateRejectsExpiredToken() {
        String rawToken = "expired-token";
        RefreshToken expired = new RefreshToken(UUID.randomUUID(), UUID.randomUUID(), Sha256.hex(rawToken),
                Instant.now().minus(1, ChronoUnit.SECONDS));
        when(refreshTokenRepository.findByTokenHash(Sha256.hex(rawToken))).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> refreshTokenService.validateAndRotate(rawToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void validateAndRotateRejectsAlreadyRevokedToken() {
        String rawToken = "revoked-token";
        RefreshToken revoked = new RefreshToken(UUID.randomUUID(), UUID.randomUUID(), Sha256.hex(rawToken),
                Instant.now().plus(1, ChronoUnit.DAYS));
        revoked.revoke();
        when(refreshTokenRepository.findByTokenHash(Sha256.hex(rawToken))).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> refreshTokenService.validateAndRotate(rawToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void revokeIsNoOpForUnknownToken() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        refreshTokenService.revoke("unknown-token");

        verify(refreshTokenRepository, times(0)).save(any());
    }

    @Test
    void revokeMarksKnownTokenRevoked() {
        String rawToken = "known-token";
        RefreshToken existing = new RefreshToken(UUID.randomUUID(), UUID.randomUUID(), Sha256.hex(rawToken),
                Instant.now().plus(1, ChronoUnit.DAYS));
        when(refreshTokenRepository.findByTokenHash(Sha256.hex(rawToken))).thenReturn(Optional.of(existing));

        refreshTokenService.revoke(rawToken);

        assertThat(existing.getRevokedAt()).isNotNull();
        verify(refreshTokenRepository).save(existing);
    }
}
