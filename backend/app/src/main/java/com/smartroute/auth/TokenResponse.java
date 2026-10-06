package com.smartroute.auth;

import java.time.Instant;

/**
 * Login/refresh result. The refresh token is deliberately not here: it travels only in an HttpOnly
 * cookie, so JavaScript (and therefore an XSS payload) can never read it.
 */
public record TokenResponse(String accessToken, String tokenType, Instant expiresAt, UserResponse user) {

    static TokenResponse bearer(AccessTokenService.IssuedToken token, UserResponse user) {
        return new TokenResponse(token.value(), "Bearer", token.expiresAt(), user);
    }
}
