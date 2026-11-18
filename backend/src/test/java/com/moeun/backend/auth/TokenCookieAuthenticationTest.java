package com.moeun.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.auth.application.JwtProperties;
import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.config.TestcontainersConfig;

import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TokenCookieAuthenticationTest.TestEndpoints.class})
class TokenCookieAuthenticationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenService tokenService;

    @Autowired
    JwtProperties jwtProperties;

    @Test
    void requestWithoutCookieGets401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/test/echo"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(cookie().maxAge(JwtTokenService.COOKIE_NAME, 0));
    }

    @Test
    void requestWithValidCookieReachesHandlerAndGetsRenewedCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/test/echo").cookie(tokenCookie(tokenService.issue(7L))))
                .andExpect(status().isOk())
                .andExpect(content().string("7"))
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(c -> assertThat(c).startsWith(JwtTokenService.COOKIE_NAME + "="));
    }

    @Test
    void authorizationHeaderIsIgnored() throws Exception {
        mockMvc.perform(get("/api/test/echo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.issue(7L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void skipRenewalHandlerDoesNotSetCookie() throws Exception {
        mockMvc.perform(get("/api/test/poll").cookie(tokenCookie(tokenService.issue(7L))))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void expiredTokenGets401() throws Exception {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneId.of("UTC"));
        String expired = new JwtTokenService(jwtProperties, past).issue(7L);

        mockMvc.perform(get("/api/test/echo").cookie(tokenCookie(expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(cookie().maxAge(JwtTokenService.COOKIE_NAME, 0));
    }

    @Test
    void corsPreflightFromAllowedOriginAllowsCredentials() throws Exception {
        mockMvc.perform(options("/api/test/echo")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    private static Cookie tokenCookie(String token) {
        return new Cookie(JwtTokenService.COOKIE_NAME, token);
    }

    @RestController
    static class TestEndpoints {

        @GetMapping("/api/test/echo")
        String echo(@AuthenticationPrincipal Jwt jwt) {
            return jwt.getSubject();
        }

        @SkipRenewal
        @GetMapping("/api/test/poll")
        String poll() {
            return "ok";
        }
    }
}
