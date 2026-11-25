package com.moeun.backend.auth.application;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.moeun.backend.auth.SkipRenewal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 인증된 사용자 조작 요청마다 만료를 30분 뒤로 미룬 새 토큰 쿠키를 붙인다(슬라이딩 만료, NFR-SEC-02). */
class TokenRenewalInterceptor implements HandlerInterceptor {

    private final JwtTokenService tokenService;

    TokenRenewalInterceptor(JwtTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || method.hasMethodAnnotation(SkipRenewal.class)) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            long memberId = Long.parseLong(jwtAuthentication.getToken().getSubject());
            String token = tokenService.issue(memberId);
            response.addHeader(HttpHeaders.SET_COOKIE, tokenService.tokenCookie(token).toString());
        }
        return true;
    }
}
