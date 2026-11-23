package com.moeun.backend.plan;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/** 대안·비교 적용 REST P-6 POST /api/plan/apply(#68). 검사 순서는 계약 C12a-9-1을 따른다. */
@SpringBootTest(properties = "moeun.demo.accounts=demo01:pw-123456")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PlanApplyApiTest {

    private static final String MONTHLY_150 = "{\"monthlySavingAmount\":1500000}";

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

    private void seedPlan() {
        saveService.save(memberId, valid().build(), null);
    }

    private ResultActions apply(String body) throws Exception {
        return mockMvc.perform(post("/api/plan/apply").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String request(String path, long expectedRevision, String conditionChanges) {
        return "{\"path\":\"" + path + "\",\"expectedRevision\":" + expectedRevision
                + ",\"conditionChanges\":" + conditionChanges + "}";
    }

    private long currentRevision() {
        return jdbc.sql("SELECT revision FROM plan WHERE member_id = ?").param(memberId).query(Long.class).single();
    }

    private <T> T currentVersion(String column, Class<T> type) {
        return jdbc.sql("SELECT v." + column + " FROM plan p JOIN plan_version v ON v.id = p.current_version_id"
                + " WHERE p.member_id = ?").param(memberId).query(type).single();
    }

    // ---- 6: 성공 ----

    @Test
    void compareApplyReturnsNewRevisionOnlyAndSavesChangedCondition() throws Exception {
        seedPlan();

        apply(request("COMPARE", 1, MONTHLY_150))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.length()").value(1));

        assertThat(currentRevision()).isEqualTo(2);
        assertThat(currentVersion("monthly_saving_amount", Long.class)).isEqualTo(1_500_000L);
        assertThat(currentVersion("source", String.class)).isEqualTo("COMPARE");
    }

    @Test
    void compareApplyCanChangeSeveralConditionsIncludingGoalAmount() throws Exception {
        seedPlan();

        apply(request("COMPARE", 1, "{\"goalAmount\":100000000,\"targetPeriodMonths\":48}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));

        assertThat(currentVersion("goal_amount", Long.class)).isEqualTo(100_000_000L);
        assertThat(currentVersion("target_period_months", Integer.class)).isEqualTo(48);
    }

    @Test
    void alternativeApplyRecordsAlternativeSource() throws Exception {
        seedPlan();

        apply(request("ALTERNATIVE", 1, "{\"targetPeriodMonths\":48}")).andExpect(status().isOk());

        assertThat(currentVersion("source", String.class)).isEqualTo("ALTERNATIVE");
    }

    @Test
    void appliedPlanCanBeUndoneWithReturnedRevision() throws Exception {
        // 되돌리기 토스트는 P-6 응답의 revision을 들고 P-5를 부른다(C12a-9-2)
        seedPlan();
        apply(request("COMPARE", 1, MONTHLY_150)).andExpect(status().isOk());

        mockMvc.perform(post("/api/plan/undo").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3));
    }

    // ---- 2: 계획 없음 ----

    @Test
    void applyWithoutPlanIs409NoPlan() throws Exception {
        apply(request("COMPARE", 1, MONTHLY_150))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NO_PLAN"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void noPlanIsCheckedBeforeConditionKeys() throws Exception {
        apply(request("ALTERNATIVE", 1, "{\"goalAmount\":100000000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_PLAN"));
    }

    // ---- 3: 조건 검증 ----

    @Test
    void alternativeCannotChangeGoalAmount() throws Exception {
        seedPlan();

        apply(request("ALTERNATIVE", 1, "{\"goalAmount\":100000000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_CHANGEABLE"))
                .andExpect(jsonPath("$.errors[0].field").value("goalAmount"))
                .andExpect(jsonPath("$.errors[0].reason").value("NOT_CHANGEABLE"));
        assertThat(currentRevision()).isEqualTo(1);
    }

    @Test
    void unknownConditionKeyIs422FieldNotChangeable() throws Exception {
        seedPlan();

        apply(request("COMPARE", 1, "{\"foo\":1}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_CHANGEABLE"))
                .andExpect(jsonPath("$.errors[0].field").value("foo"))
                .andExpect(jsonPath("$.errors[0].reason").value("NOT_CHANGEABLE"));
    }

    @Test
    void inputFieldOutsidePathIs422FieldNotChangeable() throws Exception {
        seedPlan();

        apply(request("COMPARE", 1, "{\"heldAssets\":10000000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_CHANGEABLE"))
                .andExpect(jsonPath("$.errors[0].field").value("heldAssets"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1500000\"", "true", "{\"value\":1500000}"})
    void nonNumberValueIs422NotInteger(String value) throws Exception {
        seedPlan();

        apply(request("COMPARE", 1, "{\"monthlySavingAmount\":" + value + "}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].reason").value("NOT_INTEGER"));
    }

    @Test
    void numberFormatAndCountAreCheckedBeforePathAllowance() throws Exception {
        // 검증기는 숫자 형식 → 개수 → 경로 허용·범위 순으로 보고 앞 단계에서 멈춘다(C12a-9-1 검사 3)
        seedPlan();

        apply(request("ALTERNATIVE", 1, "{\"goalAmount\":100000000,\"targetPeriodMonths\":48}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("conditionChanges"))
                .andExpect(jsonPath("$.errors[0].reason").value("TOO_MANY_CHANGES"));
    }

    @Test
    void nullValuedKeyIsNotChanged() throws Exception {
        seedPlan();
        long before = currentVersion("monthly_saving_amount", Long.class);

        apply(request("COMPARE", 1, "{\"monthlySavingAmount\":null,\"targetPeriodMonths\":48}"))
                .andExpect(status().isOk());

        assertThat(currentVersion("monthly_saving_amount", Long.class)).isEqualTo(before);
        assertThat(currentVersion("target_period_months", Integer.class)).isEqualTo(48);
    }

    @Test
    void allNullChangesIs422Required() throws Exception {
        seedPlan();

        apply(request("COMPARE", 1, "{\"monthlySavingAmount\":null}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].reason").value("REQUIRED"));
    }

    @Test
    void outOfRangeValueIs422ValidationFailedWithRange() throws Exception {
        seedPlan();

        apply(request("COMPARE", 1, "{\"monthlySavingAmount\":100000000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("monthlySavingAmount"))
                .andExpect(jsonPath("$.errors[0].reason").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.errors[0].min").value(10000))
                .andExpect(jsonPath("$.errors[0].max").value(10000000))
                .andExpect(jsonPath("$.errors[0].unit").value("KRW"));
        assertThat(currentRevision()).isEqualTo(1);
    }

    @Test
    void emptyChangesIs422Required() throws Exception {
        seedPlan();

        apply(request("COMPARE", 1, "{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].reason").value("REQUIRED"));
    }

    @Test
    void alternativeAllowsOnlyOneChangedCondition() throws Exception {
        seedPlan();

        apply(request("ALTERNATIVE", 1, "{\"targetPeriodMonths\":48,\"monthlySavingAmount\":1500000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].reason").value("TOO_MANY_CHANGES"));
    }

    @Test
    void conditionValidationIsCheckedBeforeRevision() throws Exception {
        seedPlan();

        apply(request("COMPARE", 9, "{\"monthlySavingAmount\":100000000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- 4: revision ----

    @Test
    void staleRevisionIs409PlanChangedAndPlanStaysSame() throws Exception {
        seedPlan();
        apply(request("COMPARE", 1, MONTHLY_150)).andExpect(status().isOk());

        apply(request("COMPARE", 1, "{\"targetPeriodMonths\":48}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_CHANGED"))
                .andExpect(jsonPath("$.errors").isEmpty());
        assertThat(currentRevision()).isEqualTo(2);
    }

    // ---- 1: 본문 형식 ----

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"path\":\"CHAT\",\"expectedRevision\":1,\"conditionChanges\":{\"monthlySavingAmount\":1500000}}",
        "{\"path\":\"INPUT_EDIT\",\"expectedRevision\":1,\"conditionChanges\":{\"monthlySavingAmount\":1500000}}",
        "{\"path\":\"compare\",\"expectedRevision\":1,\"conditionChanges\":{\"monthlySavingAmount\":1500000}}",
        "{\"expectedRevision\":1,\"conditionChanges\":{\"monthlySavingAmount\":1500000}}",
        "{\"path\":\"COMPARE\",\"conditionChanges\":{\"monthlySavingAmount\":1500000}}",
        "{\"path\":\"COMPARE\",\"expectedRevision\":\"one\",\"conditionChanges\":{\"monthlySavingAmount\":1500000}}",
        "{\"path\":\"COMPARE\",\"expectedRevision\":1.5,\"conditionChanges\":{\"monthlySavingAmount\":1500000}}",
        "{\"path\":\"COMPARE\",\"expectedRevision\":1}",
        "{\"path\":\"COMPARE\",\"expectedRevision\":1,\"conditionChanges\":[1500000]}",
        "[]",
        "null",
        "{\"path\":null,\"expectedRevision\":1,\"conditionChanges\":{\"monthlySavingAmount\":1500000}}",
        "{\"path\":1,\"expectedRevision\":1,\"conditionChanges\":{\"monthlySavingAmount\":1500000}}",
        "{\"path\":\"COMPARE\",\"expectedRevision\":true,\"conditionChanges\":{\"monthlySavingAmount\":1500000}}",
        "{\"path\":\"COMPARE\",\"expectedRevision\":99999999999999999999,"
                + "\"conditionChanges\":{\"monthlySavingAmount\":1500000}}",
        "{\"path\":\"COMPARE\",\"expectedRevision\":1,\"conditionChanges\":null}",
    })
    void malformedBodyIs400(String body) throws Exception {
        seedPlan();

        apply(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").isEmpty());
        assertThat(currentRevision()).isEqualTo(1);
    }

    // ---- 0: 토큰 ----

    @Test
    void applyRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/plan/apply").contentType(MediaType.APPLICATION_JSON)
                        .content(request("COMPARE", 1, MONTHLY_150)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
