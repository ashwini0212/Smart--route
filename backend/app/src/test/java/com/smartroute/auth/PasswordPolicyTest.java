package com.smartroute.auth;

import com.smartroute.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two boundaries that exist for a reason, and were not covered until Phase 15.
 *
 * <p>The upper bound is in bytes because BCrypt reads only the first 72 of them; the lower bound is in code
 * points so that a password of characters outside the BMP is not counted twice. Both are easy to "simplify"
 * into {@code String.length()}, and before these tests every other test in the suite still passed when
 * someone did — while real passwords started being silently truncated.
 */
class PasswordPolicyTest {

    @Test
    void aPasswordLongerThanSeventyTwoUtf8BytesIsRejectedRatherThanTruncated() {
        // Twenty accented characters are twenty characters and forty bytes: well inside the limit by one
        // measure, and the only one of the two that BCrypt cares about is the bytes.
        String fortyBytes = "é".repeat(20);
        assertThat(fortyBytes.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(40);
        assertThat(fortyBytes).hasSize(20);
        assertThatCode(() -> PasswordPolicy.check(fortyBytes)).doesNotThrowAnyException();

        assertThatCode(() -> PasswordPolicy.check("é".repeat(36))).doesNotThrowAnyException(); // exactly 72 bytes
        assertThatThrownBy(() -> PasswordPolicy.check("é".repeat(37)))    // 74 bytes, 37 characters
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("72 bytes");
    }

    @Test
    void lengthIsCountedInCodePointsNotChars() {
        // Twelve emoji are twelve characters to a person and twenty-four chars to Java.
        String twelveCodePoints = "🚚".repeat(12);
        String elevenCodePoints = "🚚".repeat(11);

        assertThat(twelveCodePoints).hasSize(24);
        assertThatCode(() -> PasswordPolicy.check(twelveCodePoints)).doesNotThrowAnyException();
        assertThatThrownBy(() -> PasswordPolicy.check(elevenCodePoints))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least 12");
    }

    @Test
    void theOrdinaryCasesStillHold() {
        assertThatCode(() -> PasswordPolicy.check("correct horse battery")).doesNotThrowAnyException();
        assertThatThrownBy(() -> PasswordPolicy.check("short")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PasswordPolicy.check("            ")).isInstanceOf(ApiException.class);
    }
}
