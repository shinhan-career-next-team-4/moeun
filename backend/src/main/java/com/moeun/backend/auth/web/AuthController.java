package com.moeun.backend.auth.web;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
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

    private static final String WRONG_LOGIN_MESSAGE = "아이디 또는 비밀번호를 확인해 주세요.";

    private final AuthService authService;
    private final JwtTokenService tokenService;

    AuthController(AuthService authService, JwtTokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
    }

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
        String token = tokenService.issue(memberId);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, tokenService.tokenCookie(token).toString())
                .body(new MeResponse(member.get().getLoginId(), authService.hasPlan(memberId)));
    }

    @SkipRenewal
    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, tokenService.expiredCookie().toString())
                .build();
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        long memberId = Long.parseLong(jwt.getSubject());
        return new MeResponse(authService.findMember(memberId).getLoginId(), authService.hasPlan(memberId));
    }
}
