package com.smartroute.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Security settings ({@code smartroute.security.*}). The JWT secret has no default on purpose: the app
 * refuses to start without one (see {@link SecurityConfig}).
 */
@ConfigurationProperties(prefix = "smartroute.security")
public record SecurityProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue RefreshCookie refreshCookie,
        @DefaultValue({"http://localhost:5173", "http://localhost:3000"}) List<String> corsAllowedOrigins,
        @DefaultValue LoginRateLimit loginRateLimit,
        @DefaultValue("12") int bcryptStrength,
        @DefaultValue BootstrapAdmin bootstrapAdmin,
        @DefaultValue("true") boolean publicApiDocs) {

    /**
     * @param secret          HMAC-SHA256 key, at least 32 bytes
     * @param accessTokenTtl  short, because a JWT can't be revoked before it expires
     * @param refreshTokenTtl how long a login lasts without activity
     */
    public record Jwt(String secret,
                      @DefaultValue("smartroute") String issuer,
                      @DefaultValue("15m") Duration accessTokenTtl,
                      @DefaultValue("7d") Duration refreshTokenTtl) {
    }

    /** {@code secure} is on by default; browsers accept Secure cookies on http://localhost too. */
    public record RefreshCookie(@DefaultValue("true") boolean secure) {
    }

    /**
     * Login attempts allowed per {@code period}. The per-IP limit is higher than the per-email one because
     * an office behind one NAT address shares an IP but every person has their own email.
     */
    public record LoginRateLimit(@DefaultValue("5") int perEmail,
                                 @DefaultValue("20") int perIp,
                                 @DefaultValue("1m") Duration period,
                                 @DefaultValue("100000") int maxTrackedKeys) {
    }

    /** Creates the first ADMIN on an empty user table when both values are set (e.g. from a secret store). */
    public record BootstrapAdmin(String email, String password) {

        boolean isConfigured() {
            return email != null && !email.isBlank() && password != null && !password.isBlank();
        }
    }
}
