package com.settlementengine.core.security;

import com.settlementengine.core.domain.AuditOutcome;
import com.settlementengine.core.domain.Role;
import com.settlementengine.core.domain.User;
import com.settlementengine.core.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private AuditLogService auditLogService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService, refreshTokenService, auditLogService);
    }

    @Test
    void loginWithValidCredentialsIssuesTokensWithRoleAndOwnerId() {
        UUID userId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        User user = new User(userId, "alice", "hashed", Role.READ_ONLY, ownerId);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", "hashed")).thenReturn(true);
        when(jwtService.issueAccessToken(userId, "READ_ONLY", ownerId)).thenReturn("access-token");
        when(refreshTokenService.issue(userId)).thenReturn("refresh-token");

        AuthResult result = authService.login("alice", "secret");

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        verify(auditLogService).record(userId, "LOGIN", "users", userId, AuditOutcome.SUCCESS);
    }

    @Test
    void loginWithUnknownUsernameThrowsBadCredentialsWithoutIssuingTokens() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("nobody", "whatever"))
                .isInstanceOf(BadCredentialsException.class);

        verifyNoInteractions(jwtService, refreshTokenService);
        verify(auditLogService).record(eq(null), eq("LOGIN"), eq("users"), eq(null), eq(AuditOutcome.FAILURE));
    }

    @Test
    void loginWithWrongPasswordThrowsBadCredentialsWithoutIssuingTokens() {
        User user = new User(UUID.randomUUID(), "alice", "hashed", Role.ADMIN, null);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("alice", "wrong"))
                .isInstanceOf(BadCredentialsException.class);

        verifyNoInteractions(jwtService, refreshTokenService);
        verify(auditLogService).record(eq(user.getId()), eq("LOGIN"), eq("users"), eq(null), eq(AuditOutcome.FAILURE));
    }

    @Test
    void refreshRotatesTokenAndReissuesAccessTokenFromCurrentUserState() {
        UUID userId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        User user = new User(userId, "alice", "hashed", Role.SUPPORT, ownerId);
        when(refreshTokenService.validateAndRotate("old-refresh")).thenReturn(new RotatedRefreshToken(userId, "new-refresh"));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(jwtService.issueAccessToken(userId, "SUPPORT", ownerId)).thenReturn("new-access");

        AuthResult result = authService.refresh("old-refresh");

        assertThat(result.accessToken()).isEqualTo("new-access");
        assertThat(result.refreshToken()).isEqualTo("new-refresh");
    }

    @Test
    void logoutDelegatesToRefreshTokenServiceRevoke() {
        authService.logout("some-refresh-token");

        verify(refreshTokenService).revoke("some-refresh-token");
        verify(jwtService, never()).issueAccessToken(any(), any(), any());
    }
}
