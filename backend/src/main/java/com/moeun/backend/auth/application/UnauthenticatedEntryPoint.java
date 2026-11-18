package com.moeun.backend.auth.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 보호된 엔드포인트의 쿠키 없음·서명 불일치·만료를 401 UNAUTHENTICATED로 답한다(계약 C12a-2-3).
 * 깨진 쿠키가 남지 않도록 만료 쿠키를 함께 싣는다.
 */
@Component
class UnauthenticatedEntryPoint implements AuthenticationEntryPoint {

    private static final String BODY = "{\"title\":\"Unauthorized\",\"status\":401,"
            + "\"detail\":\"로그인이 필요해요.\",\"code\":\"UNAUTHENTICATED\",\"errors\":[]}";

    private final JwtTokenService tokenService;

    UnauthenticatedEntryPoint(JwtTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.addHeader(HttpHeaders.SET_COOKIE, tokenService.expiredCookie().toString());
        response.getWriter().write(BODY);
    }
}
