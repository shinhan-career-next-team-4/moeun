package com.moeun.backend.chat.compare;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.PlanSaveService;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 조건 변경 비교 {@code POST /api/compare}(#64, FR-CMP-02). 실제 Postgres·로그인 쿠키·plan/calc 모듈을 쓴다. */
@SpringBootTest(properties = {"moeun.demo.accounts=demo01:pw-123456,demo02:pw-123456",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConditionCompareApiTest {

    private static final Duration EVENT_WAIT = Duration.ofSeconds(20);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    PlanSaveService planSaveService;

    private Cookie cookie;
    private long memberId;

    @BeforeEach
    void prepare() throws Exception {
        awaitEventsCompleted();
        jdbc.sql("DELETE FROM chat_message").update();
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        cookie = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);
        memberId = jdbc.sql("SELECT id FROM member WHERE login_id = 'demo01'").query(Long.class).single();
    }

    private void awaitEventsCompleted() {
        await().atMost(EVENT_WAIT).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }

    /** 목표 2,000만원, 36개월, 보유 500만원, 금리 비움(기본금리), 비중 5:5인 계획. */
    private void savePlan(long monthlySavingAmount) {
        planSaveService.save(memberId, valid().goalAmount(20_000_000L).targetPeriodMonths(36)
                .heldAssets(5_000_000L).monthlySavingAmount(monthlySavingAmount).build(), null);
        awaitEventsCompleted();
    }

    private MvcResult compare(String body) throws Exception {
        return mockMvc.perform(post("/api/compare").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    private JsonNode ok(String conditionChanges) throws Exception {
        MvcResult result = compare("{\"conditionChanges\":" + conditionChanges + "}");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode problem(String body, int expectedStatus) throws Exception {
        MvcResult result = compare(body);
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private List<String> scenarios(JsonNode result) {
        List<String> names = new ArrayList<>();
        result.get("scenarios").forEach(scenario -> names.add(scenario.get("scenario").asString()));
        return names;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    // ---------- 수용 기준 1: current·changed·diff, 내 계획은 그대로 ----------

    @Test
    @DisplayName("월 저축액과 기한 두 개를 바꿔 비교하면 current, changed, diff가 오고 각 조건이 바뀐 값으로 계산된다")
    void changingMonthlySavingAndPeriodReturnsCurrentChangedAndDiff() throws Exception {
        savePlan(300_000L);

        JsonNode response = ok("{\"monthlySavingAmount\":400000,\"targetPeriodMonths\":30}");

        assertThat(response.get("current").isObject()).isTrue();
        assertThat(response.get("changed").isObject()).isTrue();
        assertThat(response.get("diff").isObject()).isTrue();
        assertThat(response.get("display").isObject()).isTrue();
        JsonNode conditions = response.get("conditions");
        assertThat(conditions.get("current").get("monthlySavingAmount").asLong()).isEqualTo(300_000L);
        assertThat(conditions.get("current").get("targetPeriodMonths").asInt()).isEqualTo(36);
        assertThat(conditions.get("changed").get("monthlySavingAmount").asLong()).isEqualTo(400_000L);
        assertThat(conditions.get("changed").get("targetPeriodMonths").asInt()).isEqualTo(30);
    }

    @Test
    @DisplayName("비교해도 내 계획(plan·plan_version)은 그대로이고 calc_run(CHAT)·대화 메시지는 남지 않는다")
    void comparisonLeavesPlanAndRecordsUntouched() throws Exception {
        savePlan(300_000L);
        long revision = count("SELECT revision FROM plan");
        long versions = count("SELECT count(*) FROM plan_version");
        long currentVersion = count("SELECT current_version_id FROM plan");
        long monthly = count("SELECT monthly_saving_amount FROM plan_version WHERE id = " + currentVersion);
        long runs = count("SELECT count(*) FROM calc_run");

        ok("{\"monthlySavingAmount\":400000,\"targetPeriodMonths\":30}");
        awaitEventsCompleted();

        assertThat(count("SELECT revision FROM plan")).isEqualTo(revision);
        assertThat(count("SELECT count(*) FROM plan_version")).isEqualTo(versions);
        assertThat(count("SELECT current_version_id FROM plan")).isEqualTo(currentVersion);
        assertThat(count("SELECT monthly_saving_amount FROM plan_version WHERE id = " + currentVersion))
                .isEqualTo(monthly);
        assertThat(count("SELECT count(*) FROM calc_run")).isEqualTo(runs);
        assertThat(count("SELECT count(*) FROM calc_run WHERE run_type = 'CHAT'")).isZero();
        assertThat(count("SELECT count(*) FROM chat_message")).isZero();
    }

    @Test
    @DisplayName("값이 null인 키는 바꾸지 않음으로 보고 무시한다")
    void nullValuedKeyIsIgnored() throws Exception {
        savePlan(300_000L);

        JsonNode response = ok("{\"monthlySavingAmount\":400000,\"targetPeriodMonths\":null}");

        assertThat(response.get("conditions").get("changed").get("targetPeriodMonths").asInt()).isEqualTo(36);
    }

    // ---------- 수용 기준 2: 허용 범위 밖 값은 422 VALIDATION_FAILED ----------

    @Test
    @DisplayName("월 저축액이 허용 범위(1만원~1,000만원) 밖이면 422 VALIDATION_FAILED, OUT_OF_RANGE와 범위가 온다")
    void outOfRangeMonthlySavingIsRejected() throws Exception {
        savePlan(300_000L);

        JsonNode problem = problem("{\"conditionChanges\":{\"monthlySavingAmount\":5000}}", 422);

        assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        JsonNode error = problem.get("errors").get(0);
        assertThat(error.get("field").asString()).isEqualTo("monthlySavingAmount");
        assertThat(error.get("reason").asString()).isEqualTo("OUT_OF_RANGE");
        assertThat(error.get("min").asLong()).isEqualTo(10_000L);
        assertThat(error.get("max").asLong()).isEqualTo(10_000_000L);
    }

    @Test
    @DisplayName("범위 밖 값이 여럿이면 걸린 필드를 모두 담아 422로 거절하고 아무것도 계산하지 않는다")
    void allOutOfRangeFieldsAreReported() throws Exception {
        savePlan(300_000L);

        JsonNode problem = problem(
                "{\"conditionChanges\":{\"targetPeriodMonths\":361,\"savingsRatioPct\":101}}", 422);

        assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        List<String> fields = new ArrayList<>();
        problem.get("errors").forEach(error -> fields.add(error.get("field").asString()));
        assertThat(fields).containsExactly("targetPeriodMonths", "savingsRatioPct");
    }

    @Test
    @DisplayName("정수 자리에 소수를 보내면 422 VALIDATION_FAILED다")
    void fractionInIntegerFieldIsRejected() throws Exception {
        savePlan(300_000L);

        JsonNode problem = problem("{\"conditionChanges\":{\"monthlySavingAmount\":400000.5}}", 422);

        assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("monthlySavingAmount");
    }

    @Test
    @DisplayName("COMPARE 경로가 허용하지 않는 조건(보유 자산)은 422 VALIDATION_FAILED, NOT_CHANGEABLE이다")
    void notChangeableFieldIsRejected() throws Exception {
        savePlan(300_000L);

        JsonNode problem = problem("{\"conditionChanges\":{\"heldAssets\":1000000}}", 422);

        assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("heldAssets");
        assertThat(problem.get("errors").get(0).get("reason").asString()).isEqualTo("NOT_CHANGEABLE");
    }

    @Test
    @DisplayName("바꿀 값이 하나도 없으면 422 VALIDATION_FAILED다")
    void emptyChangesAreRejected() throws Exception {
        savePlan(300_000L);

        assertThat(problem("{\"conditionChanges\":{}}", 422).get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem("{\"conditionChanges\":{\"monthlySavingAmount\":null}}", 422).get("code").asString())
                .isEqualTo("VALIDATION_FAILED");
    }

    // ---------- 수용 기준 3: ETF 비중을 바꾸면 3구간이 함께 온다 ----------

    @Test
    @DisplayName("ETF 비중 입력(적금 30%)으로 비교하면 changed에 GOOD·AVG·BAD 3구간이 순서대로 오고 조건에 그 비중이 담긴다")
    void etfWeightComparisonReturnsThreeScenarios() throws Exception {
        savePlan(300_000L);

        JsonNode response = ok("{\"savingsRatioPct\":30}");

        assertThat(scenarios(response.get("changed"))).containsExactly("GOOD", "AVG", "BAD");
        response.get("changed").get("scenarios").forEach(scenario ->
                assertThat(scenario.get("annualReturnPct").isNull()).isFalse());
        assertThat(response.get("conditions").get("changed").get("savingsRatioPct").asInt()).isEqualTo(30);
        assertThat(response.get("diff").get("byScenario").size()).isEqualTo(3);
    }

    @Test
    @DisplayName("ETF 비중이 0%(적금 100%)면 구간이 갈리지 않아 changed는 AVG 하나다")
    void noEtfWeightHasSingleScenario() throws Exception {
        savePlan(300_000L);

        JsonNode response = ok("{\"savingsRatioPct\":100}");

        assertThat(scenarios(response.get("changed"))).containsExactly("AVG");
    }

    // ---------- 그 밖의 오류 ----------

    @Test
    @DisplayName("계획이 없으면 409 NO_PLAN이다")
    void noPlanIsRejected() throws Exception {
        JsonNode problem = problem("{\"conditionChanges\":{\"monthlySavingAmount\":400000}}", 409);

        assertThat(problem.get("code").asString()).isEqualTo("NO_PLAN");
    }

    @Test
    @DisplayName("바꾼 조건으로도 이미 목표를 넘으면 409 ALREADY_EXCEEDED다")
    void alreadyExceededIsRejected() throws Exception {
        planSaveService.save(memberId, valid().goalAmount(20_000_000L).targetPeriodMonths(36)
                .heldAssets(30_000_000L).monthlySavingAmount(300_000L).build(), null);
        awaitEventsCompleted();

        JsonNode problem = problem("{\"conditionChanges\":{\"monthlySavingAmount\":400000}}", 409);

        assertThat(problem.get("code").asString()).isEqualTo("ALREADY_EXCEEDED");
    }

    @Test
    @DisplayName("본문이 JSON 객체가 아니거나 conditionChanges가 객체가 아니면 400 MALFORMED_REQUEST다")
    void malformedBodyIsRejected() throws Exception {
        savePlan(300_000L);

        assertThat(problem("[]", 400).get("code").asString()).isEqualTo("MALFORMED_REQUEST");
        assertThat(problem("{\"conditionChanges\":5}", 400).get("code").asString()).isEqualTo("MALFORMED_REQUEST");
        assertThat(problem("{}", 400).get("code").asString()).isEqualTo("MALFORMED_REQUEST");
        assertThat(problem("{\"conditionChanges\":{\"monthlySavingAmount\":\"400000\"}}", 422).get("code")
                .asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("로그인하지 않으면 401이다")
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/api/compare").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionChanges\":{\"monthlySavingAmount\":400000}}"))
                .andExpect(status().isUnauthorized());
    }
}
