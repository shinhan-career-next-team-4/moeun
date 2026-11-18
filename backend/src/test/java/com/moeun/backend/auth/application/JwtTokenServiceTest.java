package com.moeun.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtTokenServiceTest {

    private static final String SECRET = "test-secret-at-least-32-bytes-long!!";
    private static final JwtProperties PROPS =
            new JwtProperties(SECRET, Duration.ofMinutes(30), "moeun", true);

    private final JwtTokenService service = new JwtTokenService(PROPS, Clock.system(ZoneId.of("Asia/Seoul")));
    private final JwtDecoder decoder = JwtTokenService.decoder(PROPS);

    @Test
    void issuedTokenHasSubjectIssuerAndThirtyMinuteExpiry() {
        Jwt jwt = decoder.decode(service.issue(7L));

        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("moeun");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void tokenCookieIsHttpOnlyLaxRootPath() {
        ResponseCookie cookie = service.tokenCookie("t");

        assertThat(cookie.getName()).isEqualTo("moeun_at");
        assertThat(cookie.getValue()).isEqualTo("t");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofMinutes(30));
        assertThat(cookie.isSecure()).isTrue();
    }

    @Test
    void expiredCookieHasZeroMaxAge() {
        ResponseCookie cookie = service.expiredCookie();

        assertThat(cookie.getName()).isEqualTo("moeun_at");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(cookie.isHttpOnly()).isTrue();
    }

    @Test
    void tokenSignedWithOtherSecretIsRejected() {
        JwtProperties other = new JwtProperties("other-secret-at-least-32-bytes-long!", Duration.ofMinutes(30),
                "moeun", false);
        String token = new JwtTokenService(other, Clock.systemUTC()).issue(7L);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneId.of("UTC"));
        String token = new JwtTokenService(PROPS, past).issue(7L);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
