package com.moeun.backend.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code /internal/**}(ai-server 전용) 요청의 공유 비밀 헤더를 확인한다(계약 internal-calc C2-1).
 * <p>키가 없거나 다르면 401 {@code INVALID_INTERNAL_KEY}를 이 필터가 직접 쓴다. 필터 단계라 공통 오류 처리기를 거치지
 * 않으므로 {@code instance}는 없다. 키 비교는 길이·내용에 따른 시간 차이가 없게 {@link MessageDigest#isEqual}로 한다.
 */
class InternalApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Internal-Api-Key";
    private static final String UNAUTHORIZED_BODY = "{\"type\":\"about:blank\",\"title\":\"내부 인증에 실패했어요\","
            + "\"status\":401,\"code\":\"INVALID_INTERNAL_KEY\",\"errors\":[]}";

    private final byte[] expectedKey;

    InternalApiKeyFilter(String expectedKey) {
        if (expectedKey == null || expectedKey.isBlank()) {
            throw new IllegalStateException("moeun.ai-server.internal-api-key is required");
        }
        this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String actual = request.getHeader(HEADER);
        if (actual == null || !MessageDigest.isEqual(expectedKey, actual.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(UNAUTHORIZED_BODY);
            return;
        }
        chain.doFilter(request, response);
    }
}
