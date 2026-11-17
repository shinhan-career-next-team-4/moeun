package com.moeun.backend.docs;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.config.TestcontainersConfig;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = "moeun.demo.accounts=demo01:pw-123456")
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@Import(TestcontainersConfig.class)
class AuthDocsTest {

    private static final String LOGIN_BODY = "{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}";

    @Autowired
    MockMvc mockMvc;

    @Test
    void login() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andDo(document("auth-login",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Auth")
                                .summary("데모 계정 로그인")
                                .description("성공하면 MOEUN_TOKEN(HttpOnly) 쿠키를 발급한다. 실패하면 401.")
                                .requestFields(
                                        fieldWithPath("loginId").description("아이디"),
                                        fieldWithPath("password").description("비밀번호"))
                                .responseFields(
                                        fieldWithPath("loginId").description("아이디"),
                                        fieldWithPath("hasPlan").description("저장된 내 계획이 있는지"))
                                .build())));
    }

    @Test
    void logout() throws Exception {
        mockMvc.perform(post("/api/auth/logout").cookie(loginCookie()))
                .andExpect(status().isNoContent())
                .andDo(document("auth-logout",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Auth")
                                .summary("로그아웃")
                                .description("MOEUN_TOKEN 쿠키를 지운다.")
                                .build())));
    }

    @Test
    void me() throws Exception {
        mockMvc.perform(get("/api/auth/me").cookie(loginCookie()))
                .andExpect(status().isOk())
                .andDo(document("auth-me",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Auth")
                                .summary("로그인 상태 확인")
                                .responseFields(
                                        fieldWithPath("loginId").description("아이디"),
                                        fieldWithPath("hasPlan").description("저장된 내 계획이 있는지"))
                                .build())));
    }

    private Cookie loginCookie() throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);
    }
}
