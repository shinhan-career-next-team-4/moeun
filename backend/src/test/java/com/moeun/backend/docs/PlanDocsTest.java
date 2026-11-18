package com.moeun.backend.docs;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.PlanSaveService;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = "moeun.demo.accounts=demo01:pw-123456")
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@Import(TestcontainersConfig.class)
class PlanDocsTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlanSaveService saveService;

    Cookie cookie;
    long memberId;

    @BeforeEach
    void login() throws Exception {
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        cookie = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);
        memberId = jdbc.sql("SELECT id FROM member WHERE login_id = 'demo01'").query(Long.class).single();
    }

    private void savePlan() {
        saveService.save(memberId, valid().build(), null);
    }

    @Test
    void applyPlanDoc() throws Exception {
        savePlan();
        mockMvc.perform(post("/api/plan/apply").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"COMPARE\",\"changes\":{\"monthlySavingAmount\":1500000},"
                                + "\"expectedRevision\":1}"))
                .andExpect(status().isOk())
                .andDo(document("plan-apply", resource(ResourceSnippetParameters.builder()
                        .tag("Plan").summary("바꾼 조건을 내 계획으로 적용")
                        .description("revision이 다르면 409 PLAN_CHANGED. 검증 오류는 422 VALIDATION_FAILED.")
                        .requestFields(
                                fieldWithPath("source").description("ALTERNATIVE / COMPARE / CHAT"),
                                subsectionWithPath("changes")
                                        .description("monthlySavingAmount, targetPeriodMonths, savingsRatioPct, "
                                                + "savingsRateInputPct 중 바꾼 것만"),
                                fieldWithPath("expectedRevision").description("확인창을 열 때의 revision"))
                        .responseFields(
                                fieldWithPath("planVersionId").description("새 계획 버전 id"),
                                fieldWithPath("revision").description("새 계획 revision"))
                        .build())));
    }

    @Test
    void undoPlanDoc() throws Exception {
        savePlan();
        mockMvc.perform(post("/api/plan/apply").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"CHAT\",\"changes\":{\"monthlySavingAmount\":1500000},\"expectedRevision\":1}"));
        mockMvc.perform(post("/api/plan/undo").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk())
                .andDo(document("plan-undo", resource(ResourceSnippetParameters.builder()
                        .tag("Plan").summary("직전 계획으로 되돌리기")
                        .description("한 단계까지만. 계획이 없으면 409 NO_PLAN, 되돌릴 계획이 없으면 409 NOTHING_TO_UNDO, "
                                + "revision이 다르면 409 PLAN_CHANGED.")
                        .requestFields(fieldWithPath("expectedRevision").description("현재 revision"))
                        .responseFields(fieldWithPath("revision").description("새 계획 revision"))
                        .build())));
    }
}
