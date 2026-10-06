package com.smartroute.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank @Size(max = 200) String currentPassword,
        @NotNull @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH) String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[***]";
    }
}
