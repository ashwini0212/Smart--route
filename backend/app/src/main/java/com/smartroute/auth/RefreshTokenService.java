package com.smartroute.auth;

import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Opaque refresh tokens with rotation and reuse detection.
 *
 * <ul>
 *   <li>A token is 256 random bits. Only its SHA-256 hash is stored. A fast hash is enough here (unlike
 *       passwords) because the value is random, not guessable, and a deterministic hash allows lookup.</li>
 *   <li>Every refresh revokes the presented token and issues a new one in the same family.</li>
 *   <li>Presenting a token that was already rotated means two parties hold it, so one of them stole it.
 *       The whole family is revoked: the attacker and the real user are both logged out, and the user
 *       logs in again with their password.</li>
 * </ul>
 */
@Service
class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository tokens;
    private final SecurityProperties properties;
    private final Clock clock;

    RefreshTokenService(RefreshTokenRepository tokens, SecurityProperties properties, Clock clock) {
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    /** Starts a new token family (a new login). */
    @Transactional
    IssuedRefreshToken issueNewFamily(long userId) {
        return issue(userId, UUID.randomUUID());
    }

    /**
     * Revokes the presented token and returns a successor in the same family.
     *
     * <p>{@code noRollbackFor}: when reuse is detected the family revocation must be committed even though
     * the call then fails with 401; a normal rollback would undo it.
     */
    @Transactional(noRollbackFor = ApiException.class)
    Rotation rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken token = tokens.findByTokenHash(hash(rawToken)).orElseThrow(RefreshTokenService::invalid);
        if (token.isRevoked()) {
            int revoked = tokens.revokeFamily(token.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}; revoked {} live token(s) of its family",
                    token.getUserId(), revoked);
            throw invalid();
        }
        if (token.isExpired(now)) {
            throw invalid();
        }
        token.revoke(now);
        return new Rotation(token.getUserId(), token.getFamilyId(), issue(token.getUserId(), token.getFamilyId()));
    }

    /** Logout on this device: revokes the token's family. Unknown tokens are ignored (logout is idempotent). */
    @Transactional
    void revokeFamilyOf(String rawToken) {
        tokens.findByTokenHash(hash(rawToken))
                .ifPresent(token -> tokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    @Transactional
    void revokeFamily(UUID familyId) {
        tokens.revokeFamily(familyId, clock.instant());
    }

    /** Logout everywhere: after a password change, role change or when the account is disabled. */
    @Transactional
    void revokeAllForUser(long userId) {
        tokens.revokeAllForUser(userId, clock.instant());
    }

    private IssuedRefreshToken issue(long userId, UUID familyId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = clock.instant().plus(properties.jwt().refreshTokenTtl());
        tokens.save(new RefreshToken(userId, hash(raw), familyId, expiresAt));
        return new IssuedRefreshToken(raw, expiresAt);
    }

    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    /** One message for unknown, expired, revoked and reused tokens, so the response reveals nothing. */
    private static ApiException invalid() {
        return new ApiException(ErrorCode.UNAUTHORIZED, "Refresh token is invalid or expired; please log in again");
    }

    record IssuedRefreshToken(String value, Instant expiresAt) {
    }

    record Rotation(long userId, UUID familyId, IssuedRefreshToken next) {
    }
}
