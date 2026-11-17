package com.moeun.backend.auth.application;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 설정. application.yml의 moeun.jwt.
 * 서명 키는 JWT_SECRET 환경변수로만 받는다. 없거나 32바이트 미만이거나 예전 공개 기본값이면 기동을 막는다.
 */
@ConfigurationProperties("moeun.jwt")
public record JwtProperties(String secret, Duration accessTokenTtl, String issuer, boolean cookieSecure) {

    private static final int MIN_SECRET_BYTES = 32;
    private static final String FORMER_PUBLIC_DEFAULT = "local-dev-secret-change-me-at-least-32-bytes!!";

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET 환경변수가 없습니다. backend/.env 또는 배포 환경에 넣어 주세요.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET은 32바이트 이상이어야 합니다(HS256).");
        }
        if (secret.equals(FORMER_PUBLIC_DEFAULT)) {
            throw new IllegalStateException("JWT_SECRET이 저장소에 공개된 예전 기본값입니다. 새 무작위 값으로 바꿔 주세요.");
        }
    }
}
