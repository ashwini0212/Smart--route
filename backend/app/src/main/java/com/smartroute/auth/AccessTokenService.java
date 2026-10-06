package com.smartroute.auth;

import com.smartroute.common.security.CurrentUser;
import com.smartroute.common.security.Role;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Issues short-lived, HMAC-signed JWT access tokens.
 *
 * <p>Claims: {@code sub} (user id), {@code email}, {@code role}, {@code driverId} (drivers only),
 * {@code iss}, {@code iat}, {@code exp}, {@code jti}. The role is copied from the database at login and at
 * every refresh, so a role change takes effect within one access-token lifetime at most.
 */
@Service
public class AccessTokenService {

    private final JwtEncoder encoder;
    private final SecurityProperties properties;
    private final Clock clock;

    AccessTokenService(JwtEncoder encoder, SecurityProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(AppUser user) {
        return issue(user.getId(), user.getEmail(), user.getRole(), user.getDriverId());
    }

    public IssuedToken issue(long userId, String email, Role role, Long driverId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.jwt().accessTokenTtl());
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(CurrentUser.CLAIM_EMAIL, email)
                .claim(CurrentUser.CLAIM_ROLE, role.name());
        if (driverId != null) {
            claims.claim(CurrentUser.CLAIM_DRIVER_ID, driverId);
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
