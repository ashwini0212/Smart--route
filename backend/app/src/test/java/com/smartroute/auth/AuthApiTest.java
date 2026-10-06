package com.smartroute.auth;

import com.smartroute.common.security.Role;
import com.smartroute.support.ApiTestSupport;
import com.smartroute.support.TestUsers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login, refresh rotation, reuse detection, logout and login rate limiting, end to end. */
class AuthApiTest extends ApiTestSupport {

    private static final String EMAIL = "dispatcher@test.local";

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;

    private void givenUser() {
        userId = users.create(EMAIL, Role.DISPATCHER, null);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}""".formatted(email, password)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/refresh");
        if (refreshToken != null) {
            request.cookie(new Cookie(AuthController.REFRESH_COOKIE, refreshToken));
        }
        return mockMvc.perform(request);
    }

    private static String refreshCookieOf(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    @Test
    void loginReturnsAccessTokenAndSetsAHardenedRefreshCookie() throws Exception {
        givenUser();
        MvcResult result = login(EMAIL, TestUsers.PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.user.email").value(EMAIL))
                .andExpect(jsonPath("$.user.role").value("DISPATCHER"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth", "Max-Age=");
        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
        getUrl("/api/auth/me", accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.lastLoginAt").isString());
    }

    @Test
    void emailIsCaseInsensitive() throws Exception {
        givenUser();
        login("  Dispatcher@TEST.local ", TestUsers.PASSWORD).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordUnknownEmailAndDisabledAccountLookTheSame() throws Exception {
        givenUser();
        String wrongPassword = login(EMAIL, "not-the-password").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login("nobody@test.local", "not-the-password").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        users.disable(userId);
        String disabled = login(EMAIL, TestUsers.PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        for (String response : new String[] {wrongPassword, unknownEmail, disabled}) {
            assertThat(objectMapper.readTree(response).get("message").asText()).isEqualTo("Email or password is incorrect");
            assertThat(objectMapper.readTree(response).get("code").asText()).isEqualTo("UNAUTHORIZED");
        }
    }

    @Test
    void passwordsAreStoredAsBcryptHashes() {
        givenUser();
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, userId);
        assertThat(hash).startsWith("$2a$").doesNotContain(TestUsers.PASSWORD);
    }

    @Test
    void refreshTokensAreStoredOnlyAsHashes() throws Exception {
        givenUser();
        String refreshToken = refreshCookieOf(login(EMAIL, TestUsers.PASSWORD).andReturn());
        String stored = jdbc.queryForObject("SELECT token_hash FROM refresh_token", String.class);
        assertThat(stored).hasSize(64).isNotEqualTo(refreshToken).isEqualTo(RefreshTokenService.hash(refreshToken));
    }

    @Test
    void refreshRotatesTheCookieAndIssuesANewAccessToken() throws Exception {
        givenUser();
        String first = refreshCookieOf(login(EMAIL, TestUsers.PASSWORD).andReturn());
        MvcResult refreshed = refresh(first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn();
        String second = refreshCookieOf(refreshed);
        assertThat(second).isNotEqualTo(first);
        refresh(second).andExpect(status().isOk());
    }

    @Test
    void reusingARotatedRefreshTokenRevokesTheWholeFamily() throws Exception {
        givenUser();
        String stolen = refreshCookieOf(login(EMAIL, TestUsers.PASSWORD).andReturn());
        String legitimate = refreshCookieOf(refresh(stolen).andExpect(status().isOk()).andReturn());

        // The attacker replays the old token: rejected, and the legitimate successor dies with it.
        refresh(stolen).andExpect(status().isUnauthorized());
        refresh(legitimate).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL", Long.class)).isZero();
    }

    @Test
    void reuseDetectionDoesNotTouchOtherSessions() throws Exception {
        givenUser();
        String laptop = refreshCookieOf(login(EMAIL, TestUsers.PASSWORD).andReturn());
        String phone = refreshCookieOf(login(EMAIL, TestUsers.PASSWORD).andReturn());
        refresh(laptop).andExpect(status().isOk());
        refresh(laptop).andExpect(status().isUnauthorized());
        refresh(phone).andExpect(status().isOk());
    }

    @Test
    void refreshFailsWithoutCookieOrWithGarbage() throws Exception {
        refresh(null).andExpect(status().isUnauthorized());
        refresh("not-a-real-token").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void refreshFailsForADisabledUser() throws Exception {
        givenUser();
        String token = refreshCookieOf(login(EMAIL, TestUsers.PASSWORD).andReturn());
        users.disable(userId);
        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshPicksUpARoleChange() throws Exception {
        givenUser();
        String token = refreshCookieOf(login(EMAIL, TestUsers.PASSWORD).andReturn());
        jdbc.update("UPDATE app_user SET role = 'VIEWER' WHERE id = ?", userId);
        refresh(token).andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("VIEWER"));
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
        givenUser();
        String token = refreshCookieOf(login(EMAIL, TestUsers.PASSWORD).andReturn());
        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(AuthController.REFRESH_COOKIE, token)))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        refresh(token).andExpect(status().isUnauthorized());
        // Idempotent: logging out again (or without a cookie) is fine.
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void changingPasswordRequiresTheCurrentOneAndEndsAllSessions() throws Exception {
        givenUser();
        String session = refreshCookieOf(login(EMAIL, TestUsers.PASSWORD).andReturn());
        String token = users.token(userId);
        putJson("/api/auth/password", """
                {"currentPassword":"wrong-password-here","newPassword":"a-brand-new-passphrase"}""", token)
                .andExpect(status().isBadRequest());
        putJson("/api/auth/password", """
                {"currentPassword":"%s","newPassword":"short"}""".formatted(TestUsers.PASSWORD), token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));
        putJson("/api/auth/password", """
                {"currentPassword":"%s","newPassword":"a-brand-new-passphrase"}""".formatted(TestUsers.PASSWORD), token)
                .andExpect(status().isNoContent());
        refresh(session).andExpect(status().isUnauthorized());
        login(EMAIL, TestUsers.PASSWORD).andExpect(status().isUnauthorized());
        login(EMAIL, "a-brand-new-passphrase").andExpect(status().isOk());
    }

    @Test
    void loginValidatesInput() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void loginIsRateLimitedPerEmail() throws Exception {
        givenUser();
        for (int i = 0; i < 5; i++) {
            login(EMAIL, "guess-number-" + i).andExpect(status().isUnauthorized());
        }
        // Even the right password is refused now: the limiter runs before the password check.
        login(EMAIL, TestUsers.PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "12"));
    }

    @Test
    void loginIsRateLimitedPerClientIpAcrossEmails() throws Exception {
        for (int i = 0; i < 20; i++) {
            login("victim" + i + "@test.local", "password-guess").andExpect(status().isUnauthorized());
        }
        login("victim99@test.local", "password-guess").andExpect(status().isTooManyRequests());
        // A different client is unaffected.
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"victim99@test.local\",\"password\":\"password-guess\"}")
                        .with(request -> {
                            request.setRemoteAddr("10.1.2.3");
                            return request;
                        }))
                .andExpect(status().isUnauthorized());
    }
}
