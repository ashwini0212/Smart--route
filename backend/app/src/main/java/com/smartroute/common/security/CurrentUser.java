package com.smartroute.common.security;

import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

/**
 * The authenticated caller, read from the verified access token.
 *
 * <p>Every field comes from claims the server signed itself. Nothing here can be supplied by the client:
 * a token whose role claim was edited fails signature verification before this class ever sees it.
 *
 * @param driverId the linked driver record for {@link Role#DRIVER} users, otherwise {@code null}
 */
public record CurrentUser(long id, String email, Role role, Long driverId) {

    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_DRIVER_ID = "driverId";

    public static CurrentUser fromJwt(Jwt jwt) {
        Object driverClaim = jwt.getClaim(CLAIM_DRIVER_ID);
        Long driverId = driverClaim instanceof Number number ? number.longValue() : null;
        return new CurrentUser(Long.parseLong(jwt.getSubject()), jwt.getClaimAsString(CLAIM_EMAIL),
                Role.valueOf(jwt.getClaimAsString(CLAIM_ROLE)), driverId);
    }

    /** The caller, if the request carries a valid access token. */
    public static Optional<CurrentUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return Optional.of(fromJwt(jwt));
        }
        return Optional.empty();
    }

    /** The caller; fails with 401 if there is none (should not happen behind the security filter). */
    public static CurrentUser require() {
        return find().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required"));
    }
}
