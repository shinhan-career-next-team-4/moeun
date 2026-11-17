package com.moeun.backend.auth.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** JWT 설정. application.yml의 moeun.jwt. */
@ConfigurationProperties("moeun.jwt")
public record JwtProperties(String secret, Duration accessTokenTtl, String issuer, boolean cookieSecure) {
}
