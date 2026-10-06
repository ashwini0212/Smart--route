package com.smartroute.auth;

import com.smartroute.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

import java.time.Clock;
import java.time.Duration;

/**
 * Login flow for the browser app:
 * <ol>
 *   <li>{@code POST /login} returns a 15-minute access token in the body and sets the refresh token as an
 *       HttpOnly cookie scoped to {@code /api/auth}.</li>
 *   <li>The app sends {@code Authorization: Bearer <access token>} on API calls and keeps the token in
 *       memory only (not localStorage).</li>
 *   <li>When it expires (or after a page reload) the app calls {@code POST /refresh}; the browser sends the
 *       cookie, the server rotates it and returns a new access token.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
class AuthController {

    static final String REFRESH_COOKIE = "smartroute_refresh";
    private static final String COOKIE_PATH = "/api/auth";

    private final AuthService auth;
    private final UserAccountService accounts;
    private final SecurityProperties properties;
    private final Clock clock;

    AuthController(AuthService auth, UserAccountService accounts, SecurityProperties properties, Clock clock) {
        this.auth = auth;
        this.accounts = accounts;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with email and password", security = {})
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        // With server.forward-headers-strategy=native this is the client IP reported by a trusted (private
        // network) proxy, otherwise the TCP peer. X-Forwarded-For is never read directly: clients can forge it.
        return withCookie(auth.login(request, http.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange the refresh cookie for a new access token (rotates the cookie)", security = {})
    ResponseEntity<TokenResponse> refresh(HttpServletRequest http) {
        return withCookie(auth.refresh(refreshCookie(http)));
    }

    @PostMapping("/logout")
    @Operation(summary = "End this session (revokes the refresh cookie)", security = {})
    ResponseEntity<Void> logout(HttpServletRequest http) {
        auth.logout(refreshCookie(http));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "The logged-in user")
    UserResponse me() {
        return accounts.get(CurrentUser.require().id());
    }

    @PutMapping("/password")
    @Operation(summary = "Change your password (ends all your sessions)")
    ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        accounts.changePassword(CurrentUser.require().id(), request);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    private ResponseEntity<TokenResponse> withCookie(AuthService.Session session) {
        Duration maxAge = Duration.between(clock.instant(), session.refreshExpiresAt());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), maxAge).toString())
                .body(session.body());
    }

    /**
     * HttpOnly: unreadable from JavaScript. SameSite=Strict: never sent on cross-site requests, which is
     * what makes a cookie-authenticated endpoint safe without a CSRF token. Path: only sent to /api/auth,
     * not with every API call.
     */
    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.refreshCookie().secure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    private static String refreshCookie(HttpServletRequest http) {
        Cookie cookie = WebUtils.getCookie(http, REFRESH_COOKIE);
        return cookie != null ? cookie.getValue() : null;
    }
}
