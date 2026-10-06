package com.smartroute.auth;

import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;

/**
 * Password rules: length is what matters most (NIST SP 800-63B), so there are no composition rules.
 * The upper bound exists because BCrypt only uses the first 72 <em>bytes</em>; anything longer would be
 * silently truncated, so it is rejected instead. Multi-byte characters count by their UTF-8 size.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 72;

    private PasswordPolicy() {
    }

    static void check(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Password must be at most 72 bytes in UTF-8");
        }
        if (password.isBlank() || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Password must be at least " + MIN_LENGTH + " characters");
        }
    }
}
