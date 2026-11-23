package com.moeun.backend.docs;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

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
import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.PlanApplyService;
import com.moeun.backend.plan.PlanSaveService;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.node.JsonNodeFactory;

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

    @Autowired
    PlanApplyService applyService;

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
    void undoPlanDoc() throws Exception {
        savePlan();
        applyService.apply(memberId, ChangePath.CHAT,
                Map.of("monthlySavingAmount", JsonNodeFactory.instance.numberNode(1_500_000L)), 1L, null);
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

    @Test
    void applyPlanDoc() throws Exception {
        savePlan();
        mockMvc.perform(post("/api/plan/apply").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"COMPARE\",\"expectedRevision\":1,"
                                + "\"conditionChanges\":{\"monthlySavingAmount\":1500000}}"))
                .andExpect(status().isOk())
                .andDo(document("plan-apply", resource(ResourceSnippetParameters.builder()
                        .tag("Plan").summary("대안·비교 결과를 내 계획에 적용")
                        .description("확인창을 거친 뒤 부른다. 400 MALFORMED_REQUEST, 409 NO_PLAN, "
                                + "422 FIELD_NOT_CHANGEABLE·VALIDATION_FAILED, revision이 다르면 409 PLAN_CHANGED.")
                        .requestFields(
                                fieldWithPath("path").description("COMPARE(비교 영역) 또는 ALTERNATIVE(대안 카드)"),
                                fieldWithPath("expectedRevision").description("비교를 만든 시점의 revision"),
                                subsectionWithPath("conditionChanges")
                                        .description("바꾼 조건(camelCase 키). 값이 null인 키는 바꾸지 않음"))
                        .responseFields(fieldWithPath("revision").description("새 계획 revision"))
                        .build())));
    }
}
