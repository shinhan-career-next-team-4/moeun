package com.moeun.backend.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 프론트 Origin 허용 목록. CORS_ALLOWED_ORIGINS에 쉼표로 여러 개를 넣을 수 있다. */
@ConfigurationProperties("moeun.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
