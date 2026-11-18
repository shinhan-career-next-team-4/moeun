package com.moeun.backend.auth.application;

import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 토큰을 moeun_at 쿠키에서만 읽는다. Authorization 헤더는 받지 않는다.
 * 로그인 요청과 /api 밖의 경로에서는 읽지 않는다. 만료된 쿠키가 남아 있어도 로그인·헬스 체크가 401로 막히지 않게 하기 위해서다.
 */
class CookieBearerTokenResolver implements BearerTokenResolver {

    private static final String LOGIN_PATH = "/api/auth/login";

    @Override
    public String resolve(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/") || path.equals(LOGIN_PATH)) {
            return null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (JwtTokenService.COOKIE_NAME.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
