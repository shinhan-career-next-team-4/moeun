package com.moeun.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

    private static JwtProperties withSecret(String secret) {
        return new JwtProperties(secret, Duration.ofMinutes(30), "moeun", false);
    }

    @Test
    void missingSecretFailsStartup() {
        assertThatThrownBy(() -> withSecret(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> withSecret("  ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void secretShorterThanThirtyTwoBytesFailsStartup() {
        assertThatThrownBy(() -> withSecret("a".repeat(31))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void formerPublicDefaultSecretIsRejected() {
        assertThatThrownBy(() -> withSecret("local-dev-secret-change-me-at-least-32-bytes!!"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void thirtyTwoByteSecretIsAccepted() {
        assertThatCode(() -> withSecret("a".repeat(32))).doesNotThrowAnyException();
    }
}
