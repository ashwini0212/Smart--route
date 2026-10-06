package com.smartroute.support;

import com.smartroute.auth.AccessTokenService;
import com.smartroute.auth.CreateUserRequest;
import com.smartroute.auth.UserAccountService;
import com.smartroute.auth.UserResponse;
import com.smartroute.common.security.Role;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates real users through the real service and signs real tokens for them, so tests exercise the same
 * JWT validation path as production requests.
 */
@TestComponent
public class TestUsers {

    public static final String PASSWORD = "correct-horse-battery";

    private final UserAccountService accounts;
    private final AccessTokenService tokens;
    private final JdbcTemplate jdbc;

    public TestUsers(UserAccountService accounts, AccessTokenService tokens, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.jdbc = jdbc;
    }

    public long create(String email, Role role, Long driverId) {
        return accounts.create(new CreateUserRequest(email, "Test " + role, role, driverId, PASSWORD)).id();
    }

    /** A bearer token for a new user with this role. */
    public String token(Role role) {
        return token(create(role.name().toLowerCase() + "@test.local", role, null));
    }

    public String driverToken(long driverId) {
        return token(create("driver" + driverId + "@test.local", Role.DRIVER, driverId));
    }

    public String token(long userId) {
        UserResponse user = accounts.get(userId);
        return tokens.issue(user.id(), user.email(), user.role(), user.driverId()).value();
    }

    public void disable(long userId) {
        jdbc.update("UPDATE app_user SET enabled = false WHERE id = ?", userId);
    }
}
