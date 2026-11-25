package com.moeun.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
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
@RecordApplicationEvents
class AuthApiTest {

    private static final String WRONG_LOGIN_MESSAGE = "아이디 또는 비밀번호를 확인해 주세요.";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationEvents events;

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
    void loginWithCorrectPasswordSetsCookieAndReturnsEmptyBody() throws Exception {
        MvcResult result = login("demo01", "pw-123456")
                .andExpect(status().isOk())
                .andExpect(content().json("{}"))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .startsWith(JwtTokenService.COOKIE_NAME + "=")
                .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/", "Max-Age=1800");
    }

    @Test
    void loginSucceedsWithBrokenCookieLeftOver() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .cookie(new Cookie(JwtTokenService.COOKIE_NAME, "not-a-jwt"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void loginWithWrongPasswordReturns401WithMessage() throws Exception {
        login("demo01", "wrong")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.detail").value(WRONG_LOGIN_MESSAGE));
    }

    @Test
    void loginWithBlankPasswordIs422WithRequiredError() throws Exception {
        login("demo01", "")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].reason").value("REQUIRED"));
    }

    @Test
    void loginWithUnknownIdReturnsSameMessageAsWrongPassword() throws Exception {
        login("nobody", "pw-123456")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.errors").isEmpty())
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
    void meEndpointIsGone() throws Exception {
        mockMvc.perform(get("/api/auth/me").cookie(loginCookie())).andExpect(status().isNotFound());
    }

    @Test
    void loginPublishesMemberLoggedIn() throws Exception {
        login("demo01", "pw-123456").andExpect(status().isOk());

        long memberId = memberRepository.findByLoginId("demo01").orElseThrow().getId();
        assertThat(events.stream(MemberLoggedIn.class)).extracting(MemberLoggedIn::memberId).contains(memberId);
    }

    @Test
    void demoResetByDemoAccountPublishesEvent() throws Exception {
        long memberId = memberRepository.findByLoginId("demo01").orElseThrow().getId();

        mockMvc.perform(post("/api/auth/demo-reset").cookie(loginCookie()))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));

        assertThat(events.stream(DemoReset.class)).extracting(DemoReset::memberId).contains(memberId);
    }

    @Test
    void demoResetWithoutLoginIs401() throws Exception {
        mockMvc.perform(post("/api/auth/demo-reset")).andExpect(status().isUnauthorized());

        assertThat(events.stream(DemoReset.class)).isEmpty();
    }

    @Test
    void failedLoginPublishesNothing() throws Exception {
        login("demo01", "wrong").andExpect(status().isUnauthorized());

        assertThat(events.stream(MemberLoggedIn.class)).isEmpty();
    }

    @Test
    void loginWithValidCookieStillHasExactlyOneSetCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .cookie(loginCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).hasSize(1);
    }

    @Test
    void logoutWithoutCookieIs401Unauthenticated() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void logoutPublishesMemberLoggedOut() throws Exception {
        mockMvc.perform(post("/api/auth/logout").cookie(loginCookie())).andExpect(status().isOk());

        assertThat(events.stream(MemberLoggedOut.class)).hasSize(1);
    }

    @Test
    void logoutExpiresCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/logout").cookie(loginCookie()))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"))
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
