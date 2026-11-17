package com.moeun.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.moeun.backend.auth.application.DemoAccountSeeder;
import com.moeun.backend.auth.application.JwtProperties;
import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.auth.domain.Member;
import com.moeun.backend.auth.domain.MemberRepository;
import com.moeun.backend.config.TestcontainersConfig;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = "moeun.demo.accounts=demo01:pw-123456")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AuthApiTest {

    private static final String WRONG_LOGIN_MESSAGE = "아이디 또는 비밀번호를 확인해 주세요.";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    DemoAccountSeeder seeder;

    @Autowired
    JwtProperties jwtProperties;

    @Test
    void seededAccountStoresBcryptHashNotPlaintext() {
        Member member = memberRepository.findByLoginId("demo01").orElseThrow();

        assertThat(member.getPasswordHash()).startsWith("$2").isNotEqualTo("pw-123456");
    }

    @Test
    void loginWithCorrectPasswordSetsCookieAndReturnsHasPlanFalse() throws Exception {
        MvcResult result = login("demo01", "pw-123456")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("demo01"))
                .andExpect(jsonPath("$.hasPlan").value(false))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .startsWith(JwtTokenService.COOKIE_NAME + "=")
                .contains("HttpOnly", "SameSite=Lax");
    }

    @Test
    void loginWithWrongPasswordReturns401WithMessage() throws Exception {
        login("demo01", "wrong")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(WRONG_LOGIN_MESSAGE));
    }

    @Test
    void loginWithUnknownIdReturnsSameMessageAsWrongPassword() throws Exception {
        login("nobody", "pw-123456")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(WRONG_LOGIN_MESSAGE));
    }

    @Test
    void loginSucceedsEvenWithExpiredCookieLeftOver() throws Exception {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneId.of("UTC"));
        String expired = new JwtTokenService(jwtProperties, past).issue(1L);

        mockMvc.perform(post("/api/auth/login")
                        .cookie(new Cookie(JwtTokenService.COOKIE_NAME, expired))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void meWithCookieReturnsLoginId() throws Exception {
        Cookie cookie = loginCookie();

        mockMvc.perform(get("/api/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("demo01"))
                .andExpect(jsonPath("$.hasPlan").value(false));
    }

    @Test
    void logoutExpiresCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/logout").cookie(loginCookie()))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .singleElement()
                .satisfies(c -> assertThat(c).startsWith(JwtTokenService.COOKIE_NAME + "=").contains("Max-Age=0"));
    }

    @Test
    void seederIsIdempotent() throws Exception {
        seeder.run(new DefaultApplicationArguments());
        seeder.run(new DefaultApplicationArguments());

        assertThat(memberRepository.findAll()).filteredOn(m -> m.getLoginId().equals("demo01")).hasSize(1);
    }

    private org.springframework.test.web.servlet.ResultActions login(String id, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + id + "\",\"password\":\"" + password + "\"}"));
    }

    private Cookie loginCookie() throws Exception {
        MvcResult result = login("demo01", "pw-123456").andExpect(status().isOk()).andReturn();
        return result.getResponse().getCookie(JwtTokenService.COOKIE_NAME);
    }
}
