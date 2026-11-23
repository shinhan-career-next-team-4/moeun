package com.moeun.backend.plan;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.config.TestcontainersConfig;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** 되돌리기 REST(P-5)와 plan 모듈 오류 본문. 대안·비교 적용 REST(P-6)는 PlanApplyApiTest가 본다. */
@SpringBootTest(properties = "moeun.demo.accounts=demo01:pw-123456")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PlanApiTest {

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

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private PlanSaveResult seedPlan() {
        return saveService.save(memberId, valid().build(), null);
    }

    private PlanApplyResult applyChange(long expectedRevision) {
        Map<String, JsonNode> changes = Map.of("monthlySavingAmount",
                JsonNodeFactory.instance.numberNode(1_500_000L));
        return applyService.apply(memberId, ChangePath.COMPARE, changes, expectedRevision, null);
    }

    @Test
    void inputRulesEndpointIsGone() throws Exception {
        // GET·PUT /api/plan(P-4·P-3)은 plan이 아니라 calc.web이 맡는다(#30, #31). 테스트는 calc 쪽에 있다
        mockMvc.perform(get("/api/input-rules").cookie(cookie)).andExpect(status().isNotFound());
    }

    @Test
    void undoReturnsNewRevisionOnly() throws Exception {
        seedPlan();
        applyChange(1L);

        postJson("/api/plan/undo", "{\"expectedRevision\":2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.undoAvailable").doesNotExist())
                .andExpect(jsonPath("$.plan").doesNotExist());
    }

    @Test
    void undoWithoutPlanReturns409NoPlan() throws Exception {
        postJson("/api/plan/undo", "{\"expectedRevision\":1}")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NO_PLAN"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void secondUndoReturns409NothingToUndo() throws Exception {
        seedPlan();
        applyChange(1L);
        postJson("/api/plan/undo", "{\"expectedRevision\":2}").andExpect(status().isOk());

        postJson("/api/plan/undo", "{\"expectedRevision\":3}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_UNDO"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void undoWithStaleRevisionReturns409PlanChangedWithoutCurrentPlan() throws Exception {
        seedPlan();
        applyChange(1L);

        postJson("/api/plan/undo", "{\"expectedRevision\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_CHANGED"))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.plan").doesNotExist());
    }

    @Test
    void undoWithoutExpectedRevisionIs400MalformedRequest() throws Exception {
        seedPlan();

        postJson("/api/plan/undo", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void undoWithNonIntegerExpectedRevisionIs400MalformedRequest() throws Exception {
        seedPlan();

        postJson("/api/plan/undo", "{\"expectedRevision\":\"two\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void malformedJsonIs400WithCode() throws Exception {
        postJson("/api/plan/undo", "{\"expectedRevision\":").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void unknownPathIs404WithMalformedRequestCode() throws Exception {
        mockMvc.perform(get("/api/plan/nowhere").cookie(cookie))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void unsupportedMediaTypeIs415WithMalformedRequestCode() throws Exception {
        mockMvc.perform(post("/api/plan/undo").cookie(cookie).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void planEndpointsRequireLogin() throws Exception {
        mockMvc.perform(post("/api/plan/undo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(cookie().maxAge(JwtTokenService.COOKIE_NAME, 0));
    }
}
