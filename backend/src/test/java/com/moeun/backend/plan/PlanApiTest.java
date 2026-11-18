package com.moeun.backend.plan;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
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
            {"goalType":"HOUSE","goalAmount":50000000,"targetPeriodMonths":36,"heldAssets":6000000,
             "monthlySavingAmount":1200000}""";

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
    void postPlanWithErrorsReturns422WithFieldCodes() throws Exception {
        postJson("/api/plan", "{\"goalType\":\"HOUSE\",\"heldAssets\":0}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("goalAmount", "targetPeriodMonths", "monthlySavingAmount")))
                .andExpect(jsonPath("$.errors[*].reason",
                        containsInAnyOrder("REQUIRED", "REQUIRED", "REQUIRED")));
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
                .andExpect(jsonPath("$.code").value("PLAN_CHANGED"))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.plan.monthlySaving").value(1_500_000))
                .andExpect(jsonPath("$.plan.revision").value(2));
    }

    @Test
    void applyFromInputEditSourceIsRejected() throws Exception {
        postJson("/api/plan", VALID_PLAN);

        postJson("/api/plan/apply",
                "{\"source\":\"INPUT_EDIT\",\"changes\":{\"monthlySaving\":1500000},\"expectedRevision\":1}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("source"))
                .andExpect(jsonPath("$.errors[0].reason").value("NOT_CHANGEABLE"));
    }

    @Test
    void applyWithoutSourceIsRequired() throws Exception {
        postJson("/api/plan", VALID_PLAN);

        postJson("/api/plan/apply", "{\"changes\":{},\"expectedRevision\":1}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("source"))
                .andExpect(jsonPath("$.errors[0].reason").value("REQUIRED"));
    }

    @Test
    void malformedJsonIs400WithCode() throws Exception {
        postJson("/api/plan", "{\"goalType\":").andExpect(status().isBadRequest())
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
        mockMvc.perform(post("/api/plan").cookie(cookie).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void unknownEnumValueIs400WithCode() throws Exception {
        postJson("/api/plan", "{\"goalType\":\"UNKNOWN\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void planEndpointsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/plan")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(cookie().maxAge(JwtTokenService.COOKIE_NAME, 0));
        mockMvc.perform(post("/api/plan").contentType(MediaType.APPLICATION_JSON).content(VALID_PLAN))
                .andExpect(status().isUnauthorized());
    }
}
