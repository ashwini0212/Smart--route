package com.smartroute.auth;

import com.smartroute.common.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** @param driverId required for (and only for) the DRIVER role */
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 120) String fullName,
        @NotNull Role role,
        Long driverId,
        @NotNull @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH) String password) {

    @Override
    public String toString() {
        return "CreateUserRequest[email=" + email + ", role=" + role + "]";
    }
}
