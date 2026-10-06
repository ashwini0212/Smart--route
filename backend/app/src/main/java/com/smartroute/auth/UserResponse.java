package com.smartroute.auth;

import com.smartroute.common.security.Role;

import java.time.Instant;

/** A user as returned by the API. Never contains the password hash. */
public record UserResponse(long id, String email, String fullName, Role role, Long driverId, boolean enabled,
                           Instant lastLoginAt, Instant createdAt) {

    static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.getDriverId(), user.isEnabled(), user.getLastLoginAt(), user.getCreatedAt());
    }
}
