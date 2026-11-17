package com.moeun.backend.auth.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** JWT 검증 빈과 토큰 재발급 인터셉터 등록. 필터 체인 자체는 config.SecurityConfig가 만든다. */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class AuthConfig implements WebMvcConfigurer {

    private final JwtTokenService tokenService;

    AuthConfig(JwtTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        return JwtTokenService.decoder(properties);
    }

    @Bean
    BearerTokenResolver bearerTokenResolver() {
        return new CookieBearerTokenResolver();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TokenRenewalInterceptor(tokenService)).addPathPatterns("/api/**");
    }
}
