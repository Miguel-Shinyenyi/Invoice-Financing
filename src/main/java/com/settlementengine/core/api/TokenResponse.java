package com.settlementengine.core.api;

import com.settlementengine.core.security.AuthResult;

public record TokenResponse(String accessToken, String refreshToken, String tokenType) {

    public static TokenResponse from(AuthResult result) {
        return new TokenResponse(result.accessToken(), result.refreshToken(), "Bearer");
    }
}
