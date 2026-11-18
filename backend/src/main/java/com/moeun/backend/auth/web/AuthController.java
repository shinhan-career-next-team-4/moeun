package com.moeun.backend.auth.web;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.auth.SkipRenewal;
import com.moeun.backend.auth.application.AuthService;
import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.auth.domain.Member;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);
    private static final String WRONG_LOGIN_MESSAGE = "아이디 또는 비밀번호를 확인해 주세요.";

    private final AuthService authService;
    private final JwtTokenService tokenService;

    AuthController(AuthService authService, JwtTokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
    }

    @SkipRenewal
    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<Member> member = authService.authenticate(request.loginId(), request.password());
        if (member.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, WRONG_LOGIN_MESSAGE);
            problem.setProperty("code", "INVALID_CREDENTIALS");
            problem.setProperty("errors", List.of());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
        }
        long memberId = member.get().getId();
        authService.loggedIn(memberId);
        String token = tokenService.issue(memberId);
        ResponseEntity<Map<String, Object>> response = ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, tokenService.tokenCookie(token).toString())
                .body(Map.of());
        LOG.info("auth.login memberId={}", memberId);
        return response;
    }

    @SkipRenewal
    @PostMapping("/logout")
    ResponseEntity<Map<String, Object>> logout(@AuthenticationPrincipal Jwt jwt) {
        authService.loggedOut(Long.parseLong(jwt.getSubject()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, tokenService.expiredCookie().toString())
                .body(Map.of());
    }
}
