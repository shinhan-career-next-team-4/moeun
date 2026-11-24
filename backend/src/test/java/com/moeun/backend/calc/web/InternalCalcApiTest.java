package com.moeun.backend.calc.web;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.moeun.backend.calc.CalcResultView;
import com.moeun.backend.calc.ScenarioResultView;
import com.moeun.backend.calc.application.CurrentPlanResult;
import com.moeun.backend.calc.application.CurrentPlanResultService;
import com.moeun.backend.calc.application.Formatter;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.PlanSaveService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ai-server용 비교 계산 {@code POST /internal/calc}(#32, 계약 internal-calc C2, C3). 실제 PostgreSQL로 확인한다.
 * S&P500은 레포의 테스트용 가짜 121개월을 쓴다.
 */
@SpringBootTest(properties = {"moeun.ai-server.internal-api-key=test-internal-key",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class InternalCalcApiTest {

    static final String KEY_HEADER = "X-Internal-Api-Key";
    static final String KEY = "test-internal-key";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration EVENT_WAIT = Duration.ofSeconds(20);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlanSaveService planSaveService;

    @Autowired
    CurrentPlanResultService resultService;

    @Autowired
    Formatter formatter;

    @Autowired
    TransactionTemplate transactions;

    long memberId;

    @BeforeEach
    void createMember() {
        awaitEventsCompleted();
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        jdbc.sql("DELETE FROM member WHERE login_id = 'internal-calc'").update();
        memberId = jdbc.sql("INSERT INTO member (login_id, password_hash) VALUES ('internal-calc', 'x') RETURNING id")
                .query(Long.class).single();
    }

    /** 계약 A-1과 같은 계획: 목표 2,000만원, 36개월, 보유 500만원, 월 30만원, 금리 비움(기본금리), 비중 5:5. */
    private void savePlan(long heldAssets) {
        planSaveService.save(memberId, valid().goalAmount(20_000_000L).targetPeriodMonths(36).heldAssets(heldAssets)
                .monthlySavingAmount(300_000L).build(), null);
        awaitEventsCompleted();
    }

    private void awaitEventsCompleted() {
        await().atMost(EVENT_WAIT).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }

    private ResultActions postCalc(Object body) throws Exception {
        String content = body instanceof String raw ? raw : JSON.writeValueAsString(body);
        return mockMvc.perform(post("/internal/calc").header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private Map<String, Object> request(Map<String, Object> changes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberId", memberId);
        body.put("conditionChanges", changes);
        return body;
    }

    private static Map<String, Object> changes(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            map.put((String)keyValues[index], keyValues[index + 1]);
        }
        return map;
    }

    private JsonNode readBody(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private long countChatRuns() {
        return jdbc.sql("SELECT count(*) FROM calc_run WHERE run_type = 'CHAT'").query(Long.class).single();
    }

    @Test
    @DisplayName("월 30만원 계획에 500000을 얹으면 calcRunId·conditions·current·changed·diff·display가 오고 diff는 Spring이 뺀 값이다")
    void comparesCurrentAndChanged() throws Exception {
        savePlan(5_000_000L);

        JsonNode body = readBody(postCalc(request(changes("monthlySavingAmount", 500_000))).andExpect(status().isOk()));

        assertThat(body.propertyNames()).containsExactlyInAnyOrder("calcRunId", "conditions", "current", "changed",
                "diff", "display");
        assertThat(body.get("conditions").get("current").get("monthlySavingAmount").asLong()).isEqualTo(300_000L);
        assertThat(body.get("conditions").get("changed").get("monthlySavingAmount").asLong()).isEqualTo(500_000L);
        JsonNode byScenario = body.get("diff").get("byScenario");
        assertThat(byScenario).hasSize(3);
        for (int index = 0; index < byScenario.size(); index++) {
            JsonNode current = body.get("current").get("scenarios").get(index);
            JsonNode changed = body.get("changed").get("scenarios").get(index);
            JsonNode delta = byScenario.get(index);
            assertThat(delta.get("scenario").asString()).isEqualTo(current.get("scenario").asString());
            assertThat(delta.get("afterTaxAssetDelta").asLong())
                    .isEqualTo(changed.get("afterTaxAsset").asLong() - current.get("afterTaxAsset").asLong());
            assertThat(delta.get("principalDelta").asLong())
                    .isEqualTo(changed.get("principal").asLong() - current.get("principal").asLong());
            assertThat(delta.get("afterTaxGainDelta").asLong())
                    .isEqualTo(changed.get("afterTaxGain").asLong() - current.get("afterTaxGain").asLong());
        }
        assertThat(body.get("calcRunId").asLong()).isEqualTo(jdbc.sql(
                "SELECT id FROM calc_run WHERE run_type = 'CHAT'").query(Long.class).single());
    }

    @Test
    @DisplayName("달성 여부가 바뀌면 achievementChange가 그 변화를 담는다")
    void reportsAchievementChange() throws Exception {
        savePlan(5_000_000L);

        JsonNode body = readBody(postCalc(request(changes("monthlySavingAmount", 500_000))).andExpect(status().isOk()));

        for (int index = 0; index < 3; index++) {
            boolean before = body.get("current").get("scenarios").get(index).get("isAchieved").asBoolean();
            boolean after = body.get("changed").get("scenarios").get(index).get("isAchieved").asBoolean();
            String expected = before ? (after ? "STAYS_ACHIEVED" : "ACHIEVE_TO_MISS")
                    : (after ? "MISS_TO_ACHIEVE" : "STAYS_MISSED");
            assertThat(body.get("diff").get("byScenario").get(index).get("achievementChange").asString())
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("범위 밖 값이면 422 VALIDATION_FAILED이고 minDisplay·maxDisplay가 있으며 calc_run은 없다")
    void rejectsOutOfRangeWithDisplayBounds() throws Exception {
        savePlan(5_000_000L);

        postCalc(request(changes("monthlySavingAmount", 12_000_000))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("monthlySavingAmount"))
                .andExpect(jsonPath("$.errors[0].reason").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.errors[0].min").value(10_000))
                .andExpect(jsonPath("$.errors[0].max").value(10_000_000))
                .andExpect(jsonPath("$.errors[0].unit").value("KRW"))
                .andExpect(jsonPath("$.errors[0].minDisplay").value("1만원"))
                .andExpect(jsonPath("$.errors[0].maxDisplay").value("1,000만원"));
        assertThat(countChatRuns()).isZero();
    }

    @Test
    @DisplayName("여러 필드가 걸리면 모두 담는다(TOO_MANY_DECIMALS는 scale, 기간 OUT_OF_RANGE는 0년 1개월~30년 0개월)")
    void listsAllFieldErrors() throws Exception {
        savePlan(5_000_000L);

        postCalc(request(changes("savingsRateInputPct", 3.125, "targetPeriodMonths", 400)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field == 'savingsRateInputPct')].reason").value("TOO_MANY_DECIMALS"))
                .andExpect(jsonPath("$.errors[?(@.field == 'savingsRateInputPct')].scale").value(2))
                .andExpect(jsonPath("$.errors[?(@.field == 'targetPeriodMonths')].minDisplay").value("0년 1개월"))
                .andExpect(jsonPath("$.errors[?(@.field == 'targetPeriodMonths')].maxDisplay").value("30년 0개월"));
    }

    @Test
    @DisplayName("500000.5처럼 정수 자리에 소수가 오면 잘리지 않고 422 NOT_INTEGER다")
    void rejectsDecimalInIntegerField() throws Exception {
        savePlan(5_000_000L);

        postCalc("{\"memberId\":" + memberId + ",\"conditionChanges\":{\"monthlySavingAmount\":500000.5}}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("monthlySavingAmount"))
                .andExpect(jsonPath("$.errors[0].reason").value("NOT_INTEGER"));
    }

    @Test
    @DisplayName("비중·금리 범위 밖이면 minDisplay가 비중·금리 형식이다")
    void formatsRatioAndRateBounds() throws Exception {
        savePlan(5_000_000L);

        postCalc(request(changes("savingsRatioPct", 101, "savingsRateInputPct", 21)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'savingsRatioPct')].minDisplay").value("적금 0 : ETF 100"))
                .andExpect(jsonPath("$.errors[?(@.field == 'savingsRatioPct')].maxDisplay").value("적금 100 : ETF 0"))
                .andExpect(jsonPath("$.errors[?(@.field == 'savingsRateInputPct')].minDisplay").value("연 0.00%"))
                .andExpect(jsonPath("$.errors[?(@.field == 'savingsRateInputPct')].maxDisplay").value("연 20.00%"));
    }

    @Test
    @DisplayName("계획이 없으면 409 NO_PLAN이다")
    void rejectsWithoutPlan() throws Exception {
        postCalc(request(changes("monthlySavingAmount", 500_000))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_PLAN"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    @DisplayName("초과 계획에서 목표를 안 바꾸거나 바꿔도 여전히 초과면 409 ALREADY_EXCEEDED이고 calc_run은 없다")
    void rejectsAlreadyExceededPlan() throws Exception {
        savePlan(25_000_000L);

        postCalc(request(changes("monthlySavingAmount", 500_000))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_EXCEEDED"))
                .andExpect(jsonPath("$.errors").isEmpty());
        postCalc(request(changes("goalAmount", 24_000_000))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_EXCEEDED"));
        assertThat(countChatRuns()).isZero();
    }

    @Test
    @DisplayName("초과 계획에서 목표 금액을 높여 초과를 벗어나면 200 비대칭 비교(current 초과, changed 계산)를 돌려준다")
    void allowsAsymmetricComparisonWhenExceededPlanRaisesGoalAmount() throws Exception {
        savePlan(25_000_000L);

        JsonNode body = readBody(postCalc(request(changes("goalAmount", 40_000_000))).andExpect(status().isOk()));

        assertThat(body.get("current").get("status").asString()).isEqualTo("ALREADY_EXCEEDED");
        assertThat(body.get("current").get("scenarios")).isEmpty();
        assertThat(body.get("changed").get("status").asString()).isEqualTo("CALCULATED");
        assertThat(body.get("changed").get("scenarios")).hasSize(3);
        assertThat(body.get("diff").get("byScenario")).isEmpty();
        JsonNode display = body.get("display");
        assertThat(display.has("goalAmount")).isFalse();
        assertThat(display.get("conditions.current.goalAmount").asString()).isEqualTo("약 2,000만원");
        assertThat(display.get("conditions.changed.goalAmount").asString()).isEqualTo("약 4,000만원");
        assertThat(display.get("conditions.current.heldAssets").asString()).isEqualTo("약 2,500만원");
        assertThat(display.has("conditions.changed.heldAssets")).isFalse();
    }

    @Test
    @DisplayName("목표 금액을 바꾼 비교는 시나리오마다 diff.<S>.gapToGoalDelta를 목표 대비 차이의 방향 단어로 내려준다(#285)")
    void formatsGapToGoalDeltaWhenGoalAmountChanges() throws Exception {
        savePlan(5_000_000L);

        JsonNode body = readBody(postCalc(request(changes("goalAmount", 30_000_000))).andExpect(status().isOk()));

        JsonNode display = body.get("display");
        JsonNode byScenario = body.get("diff").get("byScenario");
        assertThat(byScenario).isNotEmpty();
        for (JsonNode diff : byScenario) {
            String scenario = diff.get("scenario").asString();
            long before = gapToGoal(body.get("current"), scenario);
            long after = gapToGoal(body.get("changed"), scenario);
            assertThat(display.get("diff." + scenario + ".gapToGoalDelta").asString())
                    .isEqualTo(formatter.formatDifference(after - before))
                    .endsWith("감소")
                    .isNotEqualTo(display.get("diff." + scenario + ".afterTaxAssetDelta").asString());
        }
        assertThat(byScenario.get(0).has("gapToGoalDelta")).isFalse();
    }

    private static long gapToGoal(JsonNode side, String scenario) {
        for (JsonNode item : side.get("scenarios")) {
            if (item.get("scenario").asString().equals(scenario)) {
                return item.get("gapToGoal").asLong();
            }
        }
        throw new AssertionError("no scenario " + scenario);
    }

    @Test
    @DisplayName("목표 금액을 바꾸지 않은 비교의 gapToGoalDelta는 afterTaxAssetDelta와 같다(#285)")
    void gapToGoalDeltaEqualsAssetDeltaWhenGoalUnchanged() throws Exception {
        savePlan(5_000_000L);

        JsonNode body = readBody(postCalc(request(changes("monthlySavingAmount", 500_000))).andExpect(status().isOk()));

        JsonNode display = body.get("display");
        for (JsonNode diff : body.get("diff").get("byScenario")) {
            String prefix = "diff." + diff.get("scenario").asString() + ".";
            assertThat(display.get(prefix + "gapToGoalDelta").asString())
                    .isEqualTo(display.get(prefix + "afterTaxAssetDelta").asString());
        }
    }

    @Test
    @DisplayName("한쪽이 초과라 byScenario가 비면 gapToGoalDelta 키도 만들지 않는다(#285)")
    void omitsGapToGoalDeltaForAsymmetricComparison() throws Exception {
        savePlan(5_000_000L);

        JsonNode body = readBody(postCalc(request(changes("goalAmount", 4_000_000))).andExpect(status().isOk()));

        assertThat(body.get("diff").get("byScenario")).isEmpty();
        body.get("display").propertyNames().forEach(key -> assertThat(key).doesNotEndWith(".gapToGoalDelta"));
    }

    @Test
    @DisplayName("미달 계획에서 목표를 낮춰 changed만 초과가 되면 비대칭 비교와 changed.heldAssets를 내려준다")
    void buildsAsymmetricComparisonWhenChangedBecomesExceeded() throws Exception {
        savePlan(5_000_000L);

        JsonNode body = readBody(postCalc(request(changes("goalAmount", 4_000_000))).andExpect(status().isOk()));

        assertThat(body.get("current").get("status").asString()).isEqualTo("CALCULATED");
        assertThat(body.get("current").get("scenarios")).hasSize(3);
        assertThat(body.get("changed").get("status").asString()).isEqualTo("ALREADY_EXCEEDED");
        assertThat(body.get("changed").get("scenarios")).isEmpty();
        assertThat(body.get("diff").get("byScenario")).isEmpty();
        JsonNode display = body.get("display");
        assertThat(display.has("goalAmount")).isFalse();
        assertThat(display.get("conditions.current.goalAmount").asString()).isEqualTo("약 2,000만원");
        assertThat(display.get("conditions.changed.goalAmount").asString()).isEqualTo("약 400만원");
        assertThat(display.has("conditions.current.heldAssets")).isFalse();
        assertThat(display.get("conditions.changed.heldAssets").asString()).isEqualTo("약 500만원");
    }

    @Test
    @DisplayName("내부 키가 없거나 틀리면 401 INVALID_INTERNAL_KEY다(로그인 쿠키로는 열리지 않음)")
    void rejectsMissingOrWrongInternalKey() throws Exception {
        String body = JSON.writeValueAsString(request(changes("monthlySavingAmount", 500_000)));

        mockMvc.perform(post("/internal/calc").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_INTERNAL_KEY"))
                .andExpect(jsonPath("$.errors").isEmpty());
        mockMvc.perform(post("/internal/calc").header(KEY_HEADER, "wrong").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INTERNAL_KEY"));
    }

    @Test
    @DisplayName("내부 키는 /internal/** 에만 통한다. 사용자 API는 여전히 로그인이 필요하다")
    void keepsUserApiBehindLogin() throws Exception {
        mockMvc.perform(get("/api/plan").header(KEY_HEADER, KEY)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("시작월은 현재 계획 값이고 current·changed가 같다(D-013)")
    void keepsPlanStartMonth() throws Exception {
        savePlan(5_000_000L);
        jdbc.sql("UPDATE plan_version SET calc_start_month = DATE '2026-03-01'").update();

        JsonNode body = readBody(postCalc(request(changes("monthlySavingAmount", 500_000))).andExpect(status().isOk()));

        assertThat(body.get("current").get("calcStartMonth").asString()).isEqualTo("2026-03");
        assertThat(body.get("changed").get("calcStartMonth").asString()).isEqualTo("2026-03");
    }

    @Test
    @DisplayName("허용 키와 허용 밖 키가 섞이면 전체를 422 FIELD_NOT_CHANGEABLE로 거절하고 errors에는 허용 밖 키만 있다")
    void rejectsWholeRequestWithUnchangeableKey() throws Exception {
        savePlan(5_000_000L);

        postCalc(request(changes("goalType", 1, "monthlySavingAmount", 500_000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_CHANGEABLE"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("goalType"))
                .andExpect(jsonPath("$.errors[0].reason").value("NOT_CHANGEABLE"));
        assertThat(countChatRuns()).isZero();
    }

    @Test
    @DisplayName("두 번째 요청에 첫 번째 변경이 섞이지 않는다(D-041)")
    void doesNotAccumulateChanges() throws Exception {
        savePlan(5_000_000L);
        postCalc(request(changes("monthlySavingAmount", 500_000))).andExpect(status().isOk());

        JsonNode second = readBody(postCalc(request(changes("targetPeriodMonths", 60))).andExpect(status().isOk()));

        assertThat(second.get("conditions").get("changed").get("monthlySavingAmount").asLong()).isEqualTo(300_000L);
        assertThat(second.get("conditions").get("changed").get("targetPeriodMonths").asInt()).isEqualTo(60);
    }

    @Test
    @DisplayName("ETF 0%면 byScenario는 양쪽에 있는 AVG만이고 baselineScenario는 늘 byScenario 안이다")
    void keepsBaselinePointerInsideByScenario() throws Exception {
        savePlan(5_000_000L);

        JsonNode toSavingsOnly = readBody(postCalc(request(changes("savingsRatioPct", 100)))
                .andExpect(status().isOk()));
        assertThat(toSavingsOnly.get("changed").get("scenarios")).hasSize(1);
        assertThat(toSavingsOnly.get("diff").get("byScenario")).hasSize(1);
        assertThat(toSavingsOnly.get("diff").get("byScenario").get(0).get("scenario").asString()).isEqualTo("AVG");
        assertThat(toSavingsOnly.get("diff").get("baselineScenario").asString()).isEqualTo("AVG");

        jdbc.sql("UPDATE plan_version SET savings_ratio_pct = 100").update();
        jdbc.sql("UPDATE plan SET baseline_scenario = 'BAD'").update();
        JsonNode savingsOnly = readBody(postCalc(request(changes("monthlySavingAmount", 500_000)))
                .andExpect(status().isOk()));
        assertThat(savingsOnly.get("diff").get("byScenario")).hasSize(1);
        assertThat(savingsOnly.get("diff").get("baselineScenario").asString()).isEqualTo("AVG");

        // 보정 규칙이 실제로 쓰이는 경우: changed에는 기준 시나리오 BAD가 있지만 current(ETF 0%)에 없어 byScenario에 없다
        JsonNode toEtf = readBody(postCalc(request(changes("savingsRatioPct", 50))).andExpect(status().isOk()));
        assertThat(toEtf.get("changed").get("scenarios")).hasSize(3);
        assertThat(toEtf.get("diff").get("byScenario")).hasSize(1);
        assertThat(toEtf.get("diff").get("baselineScenario").asString()).isEqualTo("AVG");
        JsonNode display = toEtf.get("display");
        assertThat(display.has("current.AVG.afterTaxAsset")).isTrue();
        assertThat(display.has("current.GOOD.afterTaxAsset")).isFalse();
        assertThat(display.has("changed.BAD.afterTaxAsset")).isTrue();
        assertThat(display.has("diff.BAD.afterTaxAssetDelta")).isFalse();
    }

    @Test
    @DisplayName("검사 순서: null 키를 먼저 지우고, 허용 밖 키가 숫자 형식보다 먼저, 숫자 형식이 빈 변경보다 먼저다")
    void followsContractCheckOrder() throws Exception {
        savePlan(5_000_000L);

        postCalc(request(changes("goalType", null, "monthlySavingAmount", 500_000))).andExpect(status().isOk());
        postCalc(request(changes("goalType", "x"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_CHANGEABLE"));
        postCalc(request(changes("monthlySavingAmount", "x", "targetPeriodMonths", null)))
                .andExpect(status().isBadRequest());
        postCalc("{\"memberId\":" + memberId + ",\"conditionChanges\":{\"monthlySavingAmount\":1e400}}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("diff 표시: 줄면 감소, 같으면 변화 없음이다")
    void formatsDecreaseAndNoChange() throws Exception {
        savePlan(5_000_000L);

        JsonNode decrease = readBody(postCalc(request(changes("monthlySavingAmount", 200_000)))
                .andExpect(status().isOk()));
        JsonNode same = readBody(postCalc(request(changes("monthlySavingAmount", 300_000)))
                .andExpect(status().isOk()));

        assertThat(decrease.get("display").get("diff.AVG.principalDelta").asString()).endsWith("감소");
        assertThat(same.get("display").get("diff.AVG.principalDelta").asString()).isEqualTo("변화 없음");
        assertThat(same.get("display").get("diff.AVG.afterTaxAssetDelta").asString()).isEqualTo("변화 없음");
    }

    @Test
    @DisplayName("memberId가 7.5나 \"7\"이면 400 MALFORMED_REQUEST다")
    void rejectsNonIntegralMemberId() throws Exception {
        savePlan(5_000_000L);

        for (String memberIdJson : List.of("7.5", "\"" + memberId + "\"")) {
            postCalc("{\"memberId\":" + memberIdJson + ",\"conditionChanges\":{\"monthlySavingAmount\":500000}}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.errors").isEmpty());
        }
    }

    @Test
    @DisplayName("허용 키 값이 숫자가 아니거나 conditionChanges가 객체가 아니면 400 MALFORMED_REQUEST다")
    void rejectsNonNumericValuesAndNonObjectChanges() throws Exception {
        savePlan(5_000_000L);
        List<String> bodies = new ArrayList<>();
        for (String value : List.of("\"500000\"", "true", "{}", "[1]")) {
            bodies.add("{\"memberId\":" + memberId + ",\"conditionChanges\":{\"monthlySavingAmount\":" + value + "}}");
        }
        bodies.add("{\"memberId\":" + memberId + ",\"conditionChanges\":[1]}");
        bodies.add("{\"memberId\":" + memberId + "}");
        bodies.add("{\"memberId\":");

        for (String body : bodies) {
            postCalc(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        assertThat(countChatRuns()).isZero();
    }

    @Test
    @DisplayName("바꿀 값이 없으면({} 또는 모두 null) 422 REQUIRED(conditionChanges)다")
    void requiresAtLeastOneChange() throws Exception {
        savePlan(5_000_000L);
        Map<String, Object> allNull = changes("monthlySavingAmount", null, "targetPeriodMonths", null);

        for (Object changes : List.of(Map.of(), allNull)) {
            postCalc(request(castChanges(changes))).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("conditionChanges"))
                    .andExpect(jsonPath("$.errors[0].reason").value("REQUIRED"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castChanges(Object changes) {
        return (Map<String, Object>)changes;
    }

    @Test
    @DisplayName("성공하면 calc_run(CHAT) 1행이 생기고 current·changed는 같은 데이터·엔진 버전이다")
    void recordsOneChatRunWithSameVersions() throws Exception {
        savePlan(5_000_000L);

        JsonNode body = readBody(postCalc(request(changes("monthlySavingAmount", 500_000))).andExpect(status().isOk()));

        assertThat(countChatRuns()).isEqualTo(1);
        assertThat(body.get("current").get("dataVersions")).isEqualTo(body.get("changed").get("dataVersions"));
        var row = jdbc.sql("SELECT plan_version_id, savings_rate_version_id, sp500_version_id, calc_param_version_id, "
                + "engine_version FROM calc_run WHERE id = ?")
                .param(body.get("calcRunId").asLong()).query().singleRow();
        assertThat(row.get("plan_version_id")).isEqualTo(jdbc.sql("SELECT current_version_id FROM plan")
                .query(Long.class).single());
        JsonNode versions = body.get("current").get("dataVersions");
        assertThat(((Number)row.get("savings_rate_version_id")).longValue())
                .isEqualTo(versions.get("savingsRateVersionId").asLong());
        assertThat(((Number)row.get("sp500_version_id")).longValue())
                .isEqualTo(versions.get("sp500VersionId").asLong());
        assertThat(((Number)row.get("calc_param_version_id")).longValue())
                .isEqualTo(versions.get("calcParamVersionId").asLong());
        assertThat(row.get("engine_version")).isEqualTo(versions.get("engineVersion").asString());
    }

    @Test
    @DisplayName("비중을 바꾸지 않으면 conditions.changed.savingsRatioPct는 현재 해석된 비중과 같다")
    void keepsResolvedRatioWhenNotChanged() throws Exception {
        savePlan(5_000_000L);

        JsonNode body = readBody(postCalc(request(changes("monthlySavingAmount", 500_000))).andExpect(status().isOk()));

        assertThat(body.get("conditions").get("changed").get("savingsRatioPct").asInt())
                .isEqualTo(body.get("conditions").get("current").get("savingsRatioPct").asInt()).isEqualTo(50);
        assertThat(body.get("conditions").get("current").get("savingsRateInputPct").decimalValue())
                .isEqualByComparingTo("2.84");
    }

    @Test
    @DisplayName("직렬화: monthlyPoints 키가 없고 GOOD·AVG·BAD 순서, null 키 유지, 불리언 키는 isAchieved·isDefaultSavingsRate다")
    void serializesViewsByContract() throws Exception {
        savePlan(5_000_000L);

        JsonNode body = readBody(postCalc(request(changes("monthlySavingAmount", 500_000))).andExpect(status().isOk()));

        for (String side : List.of("current", "changed")) {
            JsonNode result = body.get(side);
            assertThat(result.has("requiredMonthlySaving")).isTrue();
            assertThat(result.get("assumptions").has("isDefaultSavingsRate")).isTrue();
            List<String> order = new ArrayList<>();
            for (JsonNode scenario : result.get("scenarios")) {
                order.add(scenario.get("scenario").asString());
                assertThat(scenario.has("monthlyPoints")).isFalse();
                assertThat(scenario.has("isAchieved")).isTrue();
                assertThat(scenario.has("goalReachMonth")).isTrue();
            }
            assertThat(order).containsExactly("GOOD", "AVG", "BAD");
        }
        assertThat(body.get("current").get("scenarios").get(1).get("goalReachMonth").isNull()).isTrue();
    }

    @Test
    @DisplayName("display: 있는 시나리오만, 도달 시점은 달성일 때만, diff는 방향 단어, 숫자는 공유 빌더와 같다")
    void buildsDisplayWithSharedBuilder() throws Exception {
        savePlan(5_000_000L);

        JsonNode body = readBody(postCalc(request(changes("monthlySavingAmount", 500_000))).andExpect(status().isOk()));
        JsonNode display = body.get("display");
        CurrentPlanResult planResult = transactions.execute(status ->
                resultService.findForCurrentPlan(memberId).orElseThrow());

        for (JsonNode scenario : body.get("changed").get("scenarios")) {
            String name = scenario.get("scenario").asString();
            assertThat(display.has("changed." + name + ".goalReachMonth"))
                    .isEqualTo(scenario.get("isAchieved").asBoolean() && !scenario.get("goalReachMonth").isNull());
        }
        for (String name : List.of("GOOD", "AVG", "BAD")) {
            assertThat(display.get("current." + name + ".afterTaxAsset").asString())
                    .isEqualTo(planResult.display().get(name + ".afterTaxAsset"));
            assertThat(display.get("current." + name + ".gapToGoal").asString())
                    .isEqualTo(planResult.display().get(name + ".gapToGoal"));
        }
        JsonNode avgDelta = body.get("diff").get("byScenario").get(1);
        assertThat(display.get("diff.AVG.afterTaxAssetDelta").asString())
                .isEqualTo(formatter.formatDifference(avgDelta.get("afterTaxAssetDelta").asLong()))
                .endsWith("증가");
        assertThat(display.has("conditions.current.heldAssets")).isFalse();
        assertThat(display.has("current.assumptions.taxRatePct")).isFalse();
    }

    @Test
    @DisplayName("요청의 baselineScenario는 모르는 값이어도 거절하지 않고 계획의 기준 시나리오로 계산한다")
    void ignoresRequestBaselineScenario() throws Exception {
        savePlan(5_000_000L);
        Map<String, Object> body = request(changes("monthlySavingAmount", 500_000));
        body.put("baselineScenario", "WHATEVER");

        JsonNode response = readBody(postCalc(body).andExpect(status().isOk()));

        assertThat(response.get("current").get("baselineScenario").asString()).isEqualTo("AVG");
        assertThat(response.get("diff").get("baselineScenario").asString()).isEqualTo("AVG");
    }

    @Test
    @DisplayName("응답 DTO는 calc 루트 패키지의 공개 타입이다(chat이 참조해도 모듈 경계가 깨지지 않음)")
    void keepsViewsInCalcRootPackage() {
        assertThat(CalcResultView.class.getPackageName()).isEqualTo("com.moeun.backend.calc");
        assertThat(ScenarioResultView.class.getPackageName()).isEqualTo("com.moeun.backend.calc");
    }
}
