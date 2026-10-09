package com.settlementengine.core.security;

import com.settlementengine.core.domain.AuditOutcome;
import com.settlementengine.core.domain.User;
import com.settlementengine.core.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuditLogService auditLogService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                        RefreshTokenService refreshTokenService, AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.auditLogService = auditLogService;
    }

    public AuthResult login(String username, String password) {
        var userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty() || !passwordEncoder.matches(password, userOpt.get().getPasswordHash())) {
            auditLogService.record(userOpt.map(User::getId).orElse(null), "LOGIN", "users", null, AuditOutcome.FAILURE);
            throw new BadCredentialsException("Invalid username or password");
        }
        User user = userOpt.get();
        auditLogService.record(user.getId(), "LOGIN", "users", user.getId(), AuditOutcome.SUCCESS);
        return issueTokensFor(user);
    }

    public AuthResult refresh(String rawRefreshToken) {
        RotatedRefreshToken rotated = refreshTokenService.validateAndRotate(rawRefreshToken);
        User user = userRepository.findById(rotated.userId())
                .orElseThrow(() -> new IllegalStateException("User " + rotated.userId() + " no longer exists"));
        String accessToken = jwtService.issueAccessToken(user.getId(), user.getRole().name(), user.getOwnerId());
        return new AuthResult(accessToken, rotated.rawToken());
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    private AuthResult issueTokensFor(User user) {
        String accessToken = jwtService.issueAccessToken(user.getId(), user.getRole().name(), user.getOwnerId());
        String refreshToken = refreshTokenService.issue(user.getId());
        return new AuthResult(accessToken, refreshToken);
    }
}
