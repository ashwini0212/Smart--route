package com.smartroute.auth;

import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/** Login, token refresh and logout. */
@Service
public class AuthService {

    private static final String BAD_CREDENTIALS = "Email or password is incorrect";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;
    private final LoginRateLimiter rateLimiter;
    private final Clock clock;
    /**
     * Hash of a random password, checked when the email is unknown. Without it, "unknown email" returns
     * in microseconds and "wrong password" takes a full BCrypt check, and the timing difference tells an
     * attacker which emails have accounts.
     */
    private final String dummyHash;

    AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, AccessTokenService accessTokens,
                RefreshTokenService refreshTokens, LoginRateLimiter rateLimiter, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public Session login(LoginRequest request, String clientIp) {
        String email = UserAccountService.normalizeEmail(request.email());
        rateLimiter.check(clientIp, email);
        Optional<AppUser> found = users.findByEmail(email);
        String hash = found.map(AppUser::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        // One message for unknown email, wrong password and disabled account: no account enumeration.
        if (found.isEmpty() || !passwordMatches || !found.get().isEnabled()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        AppUser user = found.get();
        user.recordLogin(clock.instant());
        return session(user, refreshTokens.issueNewFamily(user.getId()));
    }

    /** Rotates the refresh token. The access token gets the user's <em>current</em> role from the database. */
    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "No refresh token; please log in");
        }
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken);
        AppUser user = users.findById(rotation.userId()).orElseThrow(
                () -> new ApiException(ErrorCode.UNAUTHORIZED, "Account no longer exists"));
        if (!user.isEnabled()) {
            refreshTokens.revokeFamily(rotation.familyId());
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Account is disabled");
        }
        return session(user, rotation.next());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokens.revokeFamilyOf(rawRefreshToken);
        }
    }

    private Session session(AppUser user, RefreshTokenService.IssuedRefreshToken refresh) {
        TokenResponse body = TokenResponse.bearer(accessTokens.issue(user), UserResponse.from(user));
        return new Session(body, refresh.value(), refresh.expiresAt());
    }

    /** What the controller needs: the JSON body plus the refresh token for the cookie. */
    public record Session(TokenResponse body, String refreshToken, java.time.Instant refreshExpiresAt) {

        @Override
        public String toString() {
            return "Session[user=" + body.user().id() + "]";
        }
    }
}
