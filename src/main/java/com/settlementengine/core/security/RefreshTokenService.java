package com.settlementengine.core.security;

import com.settlementengine.core.domain.RefreshToken;
import com.settlementengine.core.repository.RefreshTokenRepository;
import com.settlementengine.core.util.Sha256;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final long ttlDays;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                                @Value("${settlement-engine.refresh-token.ttl-days:30}") long ttlDays) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.ttlDays = ttlDays;
    }

    public String issue(UUID userId) {
        String rawToken = generateRawToken();
        RefreshToken token = new RefreshToken(UUID.randomUUID(), userId, Sha256.hex(rawToken),
                Instant.now().plus(ttlDays, ChronoUnit.DAYS));
        refreshTokenRepository.save(token);
        return rawToken;
    }

    public RotatedRefreshToken validateAndRotate(String rawToken) {
        RefreshToken existing = refreshTokenRepository.findByTokenHash(Sha256.hex(rawToken))
                .orElseThrow(() -> new InvalidTokenException("Refresh token not recognized"));
        if (!existing.isActive(Instant.now())) {
            throw new InvalidTokenException("Refresh token expired or revoked");
        }
        existing.revoke();
        refreshTokenRepository.save(existing);
        String newRawToken = issue(existing.getUserId());
        return new RotatedRefreshToken(existing.getUserId(), newRawToken);
    }

    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(Sha256.hex(rawToken)).ifPresent(existing -> {
            existing.revoke();
            refreshTokenRepository.save(existing);
        });
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
