package com.smartroute.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Login credentials. Only format is validated; password rules apply when a password is set, not here. */
public record LoginRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 200) String password) {

    @Override
    public String toString() {
        // Keep the password out of logs and exception messages.
        return "LoginRequest[email=" + email + "]";
    }
}
