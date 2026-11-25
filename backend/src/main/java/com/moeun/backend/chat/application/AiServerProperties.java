package com.moeun.backend.chat.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** ai-server 호출 설정. application.yml의 moeun.ai-server. */
@ConfigurationProperties("moeun.ai-server")
public record AiServerProperties(String baseUrl, String internalApiKey, Duration connectTimeout,
        Duration readTimeout) {
}
