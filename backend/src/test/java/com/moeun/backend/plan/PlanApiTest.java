package com.moeun.backend.plan;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest(properties = "moeun.demo.accounts=demo01:pw-123456")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PlanApiTest {

    static final String VALID_PLAN = """
            {"goalType":"JEONSE","goalAmount":50000000,"targetPeriodMonths":36,"heldAssets":6000000,
             "monthlySaving":1200000}""";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    Cookie cookie;

    @BeforeEach
    void login() throws Exception {
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        cookie = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void getPlanWithoutPlanIs404() throws Exception {
        mockMvc.perform(get("/api/plan").cookie(cookie)).andExpect(status().isNotFound());
    }

    @Test
    void postPlanReturnsViewAndThenMeHasPlanTrue() throws Exception {
        postJson("/api/plan", VALID_PLAN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalAmount").value(50_000_000))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.canUndo").value(false))
                .andExpect(jsonPath("$.baselineScenario").value("AVG"));

        mockMvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(jsonPath("$.hasPlan").value(true));
        mockMvc.perform(get("/api/plan").cookie(cookie)).andExpect(jsonPath("$.monthlySaving").value(1_200_000));
    }

    @Test
    void postPlanWithErrorsReturns400WithFieldCodes() throws Exception {
        postJson("/api/plan", "{\"goalType\":\"JEONSE\",\"heldAssets\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("goalAmount", "targetPeriodMonths")))
                .andExpect(jsonPath("$.errors[*].code",
                        containsInAnyOrder("REQUIRED", "ONE_OF_PERIOD_OR_SAVING")));
    }

    @Test
    void applyAndUndoRoundTrip() throws Exception {
        postJson("/api/plan", VALID_PLAN);

        postJson("/api/plan/apply",
                "{\"source\":\"COMPARE\",\"changes\":{\"monthlySaving\":1500000},\"expectedRevision\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlySaving").value(1_500_000))
                .andExpect(jsonPath("$.canUndo").value(true));
        postJson("/api/plan/undo", "{\"expectedRevision\":2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlySaving").value(1_200_000))
                .andExpect(jsonPath("$.canUndo").value(false));
    }

    @Test
    void applyWithStaleRevisionReturns409WithCurrentPlan() throws Exception {
        postJson("/api/plan", VALID_PLAN);
        postJson("/api/plan/apply",
                "{\"source\":\"CHAT\",\"changes\":{\"monthlySaving\":1500000},\"expectedRevision\":1}");

        postJson("/api/plan/apply",
                "{\"source\":\"CHAT\",\"changes\":{\"monthlySaving\":1600000},\"expectedRevision\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.plan.monthlySaving").value(1_500_000))
                .andExpect(jsonPath("$.plan.revision").value(2));
    }

    @Test
    void applyFromInputEditSourceIsRejected() throws Exception {
        postJson("/api/plan", VALID_PLAN);

        postJson("/api/plan/apply",
                "{\"source\":\"INPUT_EDIT\",\"changes\":{\"monthlySaving\":1500000},\"expectedRevision\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("source"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        postJson("/api/plan", "{\"goalType\":\"UNKNOWN\"}").andExpect(status().isBadRequest());
    }

    @Test
    void inputRulesReturnsCamelCaseFieldsAndGoalDefaults() throws Exception {
        mockMvc.perform(get("/api/input-rules").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.goalAmount.max").value(1_000_000_000))
                .andExpect(jsonPath("$.fields.goalAmount.step").value(10_000))
                .andExpect(jsonPath("$.fields.savingsRatePct.scale").value(2))
                .andExpect(jsonPath("$.goalTypes[0].code").value("JEONSE"))
                .andExpect(jsonPath("$.goalTypes[0].defaultAmount").value(100_000_000))
                .andExpect(jsonPath("$.goalTypes[4].code").value("CUSTOM"))
                .andExpect(jsonPath("$.goalTypes[4].defaultAmount").doesNotExist())
                .andExpect(jsonPath("$.periodPresets").value(org.hamcrest.Matchers.contains(3, 6, 12, 24, 36)))
                .andExpect(jsonPath("$.baseSavingsRatioPct").value(50));
    }

    @Test
    void planEndpointsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/plan")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/input-rules")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/plan").contentType(MediaType.APPLICATION_JSON).content(VALID_PLAN))
                .andExpect(status().isUnauthorized());
    }
}
