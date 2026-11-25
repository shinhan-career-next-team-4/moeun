package com.moeun.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.auth.domain.Member;
import com.moeun.backend.auth.domain.MemberRepository;
import com.moeun.backend.config.TestcontainersConfig;

import jakarta.servlet.http.Cookie;

/** P-7 회원가입(FR-AUTH-04, D-107). */
@SpringBootTest(properties = "moeun.demo.accounts=demo01:pw-123456")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@RecordApplicationEvents
class SignupApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    MemberRepository memberRepository;

    @Test
    void signupSetsCookieThatAuthenticatesAndAccountCanLogIn() throws Exception {
        MvcResult result = signup("saver01", "pass-1234")
                .andExpect(status().isOk())
                .andExpect(content().json("{}"))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .startsWith(JwtTokenService.COOKIE_NAME + "=")
                .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/", "Max-Age=1800");
        Cookie cookie = result.getResponse().getCookie(JwtTokenService.COOKIE_NAME);
        // 새 계정은 계획이 없다: 인증은 통과하고 409
        mockMvc.perform(get("/api/plan").cookie(cookie)).andExpect(status().isConflict());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"saver01\",\"password\":\"pass-1234\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void signupStoresBcryptHashOnly() throws Exception {
        signup("saver02", "pass-1234").andExpect(status().isOk());

        Member member = memberRepository.findByLoginId("saver02").orElseThrow();
        assertThat(member.getPasswordHash()).startsWith("$2").isNotEqualTo("pass-1234");
    }

    @Test
    void signupPublishesMemberLoggedIn() throws Exception {
        signup("saver03", "pass-1234").andExpect(status().isOk());

        long memberId = memberRepository.findByLoginId("saver03").orElseThrow().getId();
        assertThat(events.stream(MemberLoggedIn.class)).extracting(MemberLoggedIn::memberId).containsExactly(memberId);
    }

    @Test
    void duplicateLoginIdIs409AndCreatesNothing() throws Exception {
        long before = memberRepository.count();

        signup("demo01", "another-pass")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_LOGIN_ID"))
                .andExpect(jsonPath("$.errors").isEmpty());

        assertThat(memberRepository.count()).isEqualTo(before);
        assertThat(events.stream(MemberLoggedIn.class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "abcdefghijklmnopqrstu", "saver_01", "저축왕01", "saver 01"})
    void malformedLoginIdIs422InvalidFormat(String loginId) throws Exception {
        signup(loginId, "pass-1234")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("loginId"))
                .andExpect(jsonPath("$.errors[0].reason").value("INVALID_FORMAT"));
        assertThat(memberRepository.findByLoginId(loginId)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 65})
    void passwordOutsideLengthIs422InvalidFormat(int length) throws Exception {
        signup("saver04", "p".repeat(length))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].reason").value("INVALID_FORMAT"));
        assertThat(memberRepository.findByLoginId("saver04")).isEmpty();
    }

    /** BCrypt는 72바이트를 넘으면 해시하지 않는다. 한글 25자(75바이트)는 64자 안이어도 형식 위반이다. */
    @Test
    void passwordOver72Utf8BytesIs422InvalidFormat() throws Exception {
        signup("saver07", "가".repeat(25))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].reason").value("INVALID_FORMAT"));
        assertThat(memberRepository.findByLoginId("saver07")).isEmpty();
    }

    @Test
    void passwordOf72Utf8BytesIsAccepted() throws Exception {
        signup("saver08", "가".repeat(24)).andExpect(status().isOk());
    }

    @Test
    void missingFieldIs422Required() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pass-1234\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("loginId"))
                .andExpect(jsonPath("$.errors[0].reason").value("REQUIRED"));
    }

    @Test
    void boundaryLengthsAreAccepted() throws Exception {
        signup("abcd", "p".repeat(8)).andExpect(status().isOk());
        signup("a".repeat(20), "p".repeat(64)).andExpect(status().isOk());
    }

    @Test
    void bothFieldsMalformedReportsBoth() throws Exception {
        signup("ab", "short")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("loginId"))
                .andExpect(jsonPath("$.errors[1].field").value("password"));
    }

    @Test
    void blankValueIs422Required() throws Exception {
        signup("saver05", "")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].reason").value("REQUIRED"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("[1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void signupSucceedsWithBrokenCookieLeftOver() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .cookie(new Cookie(JwtTokenService.COOKIE_NAME, "not-a-jwt"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"saver06\",\"password\":\"pass-1234\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions signup(String id, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + id + "\",\"password\":\"" + password + "\"}"));
    }
}
