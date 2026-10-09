package com.settlementengine.core.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtService {

    private final SecretKey key;
    private final Duration accessTokenTtl;

    public JwtService(@Value("${settlement-engine.jwt.secret}") String secret,
                       @Value("${settlement-engine.jwt.access-token-ttl-seconds:900}") long accessTokenTtlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = Duration.ofSeconds(accessTokenTtlSeconds);
    }

    public String issueAccessToken(UUID userId, String role, UUID ownerId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)));
        if (ownerId != null) {
            builder.claim("ownerId", ownerId.toString());
        }
        return builder.signWith(key).compact();
    }

    public AccessTokenClaims parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String ownerId = claims.get("ownerId", String.class);
            return new AccessTokenClaims(
                    UUID.fromString(claims.getSubject()),
                    claims.get("role", String.class),
                    ownerId == null ? null : UUID.fromString(ownerId));
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("Access token invalid or expired", e);
        }
    }
}
