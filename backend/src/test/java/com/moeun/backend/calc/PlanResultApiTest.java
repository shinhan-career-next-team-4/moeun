package com.moeun.backend.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.config.TestcontainersConfig;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * P-3 입력 저장 {@code PUT /api/plan}(#30, 계약 C12a-5, C12a-6-1, C12a-6-2). 실제 PostgreSQL·로그인 쿠키로 확인한다.
 * S&P500은 레포의 테스트용 가짜 121개월을 쓴다.
 */
@SpringBootTest(properties = {"moeun.demo.accounts=demo01:pw-123456",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PlanResultApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration EVENT_WAIT = Duration.ofSeconds(20);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CurrentPlanResultService resultService;

    @Autowired
    TransactionTemplate transactions;

    Cookie cookie;
    long memberId;

    @BeforeEach
    void login() throws Exception {
        awaitEventsCompleted();
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        cookie = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);
        memberId = jdbc.sql("SELECT id FROM member WHERE login_id = 'demo01'").query(Long.class).single();
    }

    /** 계약 C12a-5-1 예시 요청. 값을 바꾸려면 덮어쓴다. */
    private static Map<String, Object> request() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedRevision", null);
        body.put("goalType", "HOUSE");
        body.put("goalCustomName", null);
        body.put("goalAmount", 20_000_000L);
        body.put("targetPeriodMonths", 36);
        body.put("heldAssets", 5_000_000L);
        body.put("monthlySavingAmount", 300_000L);
        body.put("savingsRateInputPct", null);
        body.put("savingsRatioPct", null);
        return body;
    }

    private ResultActions putPlan(Object body) throws Exception {
        String content = body instanceof String raw ? raw : JSON.writeValueAsString(body);
        return mockMvc.perform(put("/api/plan").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(content));
    }

    private JsonNode readBody(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private void awaitEventsCompleted() {
        await().atMost(EVENT_WAIT).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }

    @Test
    @DisplayName("올바른 입력이면 계획을 저장하고 revision·calcRunId·결과·display를 돌려준다")
    void savesPlanAndReturnsResult() throws Exception {
        JsonNode body = readBody(putPlan(request()).andExpect(status().isOk()));

        assertThat(count("plan_version")).isEqualTo(1);
        assertThat(body.get("revision").asLong()).isEqualTo(1L);
        assertThat(body.get("calcRunId").asLong()).isEqualTo(jdbc.sql("SELECT id FROM calc_run")
                .query(Long.class).single());
        assertThat(body.get("baselineResultScenario").asString()).isEqualTo("AVG");
        assertThat(body.get("result").get("status").asString()).isEqualTo("CALCULATED");
        assertThat(body.get("result").get("scenarios")).hasSize(3);
        assertThat(body.get("plan").get("goalAmount").asLong()).isEqualTo(20_000_000L);
    }

    @Test
    @DisplayName("응답 필드는 계약 C12a-6-1 그대로이고 화면이 쓰지 않는 필드와 문구 키 목록은 없다")
    void exposesOnlyContractFields() throws Exception {
        JsonNode body = readBody(putPlan(request()).andExpect(status().isOk()));

        assertThat(body.propertyNames()).containsExactlyInAnyOrder("revision", "calcRunId",
                "baselineResultScenario", "undoAvailable", "plan", "result", "display");
        assertThat(body.get("plan").propertyNames()).containsExactlyInAnyOrder("goalType", "goalCustomName",
                "goalAmount", "targetPeriodMonths", "heldAssets", "monthlySavingAmount", "savingsRateInputPct",
                "savingsRatioPct", "etfProductCode");
        assertThat(body.get("plan").has("savingsRateInputPct")).isTrue();
        assertThat(body.get("plan").get("savingsRatioPct").isNull()).isTrue();
        assertThat(body.get("plan").get("etfProductCode").asString()).isEqualTo("SP500");   // 새 계획은 대표 상품(#154)
        assertThat(body.get("result").propertyNames()).containsExactlyInAnyOrder("status", "goalAmount",
                "calcStartMonth", "targetMonth", "scenarios", "assumptions");
        JsonNode scenario = body.get("result").get("scenarios").get(0);
        assertThat(scenario.has("annualReturnPct")).isFalse();
        assertThat(scenario.has("goalReachMonth")).isTrue();
        assertThat(body.toString()).doesNotContain("tax.note", "currentVersionId", "dataVersions", "memberId",
                "requiredMonthlySaving");
    }

    @Test
    @DisplayName("monthlyPoints는 month·afterTaxAsset만 있고 엔진이 만든 점을 수·값 그대로 복사한다")
    void copiesEngineMonthlyPoints() throws Exception {
        JsonNode body = readBody(putPlan(request()).andExpect(status().isOk()));
        CurrentPlanResult engineResult = transactions.execute(status ->
                resultService.findForCurrentPlan(memberId).orElseThrow());

        JsonNode points = body.get("result").get("scenarios").get(1).get("monthlyPoints");
        List<MonthlyPoint> enginePoints = engineResult.result().scenarios().get(1)
                .monthlyPoints();
        assertThat(points).hasSize(enginePoints.size()).hasSize(37);
        for (int k = 0; k < enginePoints.size(); k++) {
            assertThat(points.get(k).propertyNames()).containsExactlyInAnyOrder("month", "afterTaxAsset");
            assertThat(points.get(k).get("month").asString()).isEqualTo(enginePoints.get(k).month().toString());
            assertThat(points.get(k).get("afterTaxAsset").asLong()).isEqualTo(enginePoints.get(k).afterTaxAsset());
        }
    }

    @Test
    @DisplayName("display는 공유 ResultDisplayBuilder 결과와 같다(같은 계획 버전의 결과 조회 서비스와 비교)")
    void usesSharedDisplayBuilder() throws Exception {
        JsonNode body = readBody(putPlan(request()).andExpect(status().isOk()));
        CurrentPlanResult engineResult = transactions.execute(status ->
                resultService.findForCurrentPlan(memberId).orElseThrow());

        Map<String, String> display = new LinkedHashMap<>();
        body.get("display").properties().forEach(entry -> display.put(entry.getKey(), entry.getValue().asString()));
        // 화면 전용 배지 키(<S>.gapBadge, #224)는 공유 display에 없고 이 응답에서만 더해진다
        Map<String, String> shared = new LinkedHashMap<>(display);
        shared.keySet().removeIf(key -> key.endsWith(".gapBadge"));
        assertThat(shared).isEqualTo(engineResult.display());
        assertThat(display).containsKeys("GOOD.gapBadge", "AVG.gapBadge", "BAD.gapBadge");
        assertThat(display).containsEntry("assumptions.taxRatePct", "15.4%");
    }

    @Test
    @DisplayName("금리를 비우면 기본금리 사용 표시와 기준월이 가정 보기·display에 있다")
    void marksDefaultSavingsRate() throws Exception {
        JsonNode body = readBody(putPlan(request()).andExpect(status().isOk()));

        JsonNode assumptions = body.get("result").get("assumptions");
        assertThat(assumptions.get("isDefaultSavingsRate").asBoolean()).isTrue();
        assertThat(assumptions.get("savingsRateBaseMonth").asString()).isEqualTo("2026-10");
        assertThat(assumptions.get("sp500BaseDate").asString()).isEqualTo("2026-10-31");
        assertThat(body.get("display").get("assumptions.savingsRateBaseMonth").asString()).isEqualTo("2026년 10월");
    }

    @Test
    @DisplayName("보유 자산 0원이면 display가 0원이고, 미달 시나리오에는 도달 시점 키가 없다")
    void keepsBuilderBoundaryRules() throws Exception {
        Map<String, Object> zeroHeld = request();
        zeroHeld.put("heldAssets", 0L);

        JsonNode body = readBody(putPlan(zeroHeld).andExpect(status().isOk()));

        assertThat(body.get("display").get("conditions.heldAssets").asString()).isEqualTo("0원");
        for (JsonNode scenario : body.get("result").get("scenarios")) {
            String key = scenario.get("scenario").asString() + ".goalReachMonth";
            assertThat(body.get("display").has(key)).isEqualTo(scenario.get("isAchieved").asBoolean()
                    && !scenario.get("goalReachMonth").isNull());
        }
    }

    @Test
    @DisplayName("보유 자산 ≥ 목표면 ALREADY_EXCEEDED이고 revision·calcRunId는 있으며 기준 결과 시나리오는 null이다")
    void returnsAlreadyExceeded() throws Exception {
        Map<String, Object> exceeded = request();
        exceeded.put("heldAssets", 25_000_000L);

        JsonNode body = readBody(putPlan(exceeded).andExpect(status().isOk()));

        assertThat(body.get("result").get("status").asString()).isEqualTo("ALREADY_EXCEEDED");
        assertThat(body.get("result").get("scenarios")).isEmpty();
        assertThat(body.get("baselineResultScenario").isNull()).isTrue();
        assertThat(body.get("revision").asLong()).isEqualTo(1L);
        assertThat(body.get("calcRunId").isNumber()).isTrue();
    }

    @Test
    @DisplayName("범위 밖 월 저축액이면 422 VALIDATION_FAILED, errors에 min·max·unit은 있고 minDisplay는 없으며 저장하지 않는다")
    void rejectsOutOfRangeWithoutSaving() throws Exception {
        Map<String, Object> outOfRange = request();
        outOfRange.put("monthlySavingAmount", 10_000_001L);

        putPlan(outOfRange).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("monthlySavingAmount"))
                .andExpect(jsonPath("$.errors[0].reason").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.errors[0].min").value(10_000))
                .andExpect(jsonPath("$.errors[0].max").value(10_000_000))
                .andExpect(jsonPath("$.errors[0].unit").value("KRW"))
                .andExpect(jsonPath("$.errors[0].minDisplay").doesNotExist())
                .andExpect(jsonPath("$.errors[0].maxDisplay").doesNotExist());
        assertThat(count("plan_version")).isZero();
    }

    @Test
    @DisplayName("expectedRevision·savingsRateInputPct·savingsRatioPct 키가 없으면 422 REQUIRED다")
    void requiresNullableKeys() throws Exception {
        Map<String, Object> noRevision = request();
        noRevision.remove("expectedRevision");
        Map<String, Object> noRate = request();
        noRate.remove("savingsRateInputPct");
        Map<String, Object> noRatio = request();
        noRatio.remove("savingsRatioPct");

        putPlan(noRevision).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("expectedRevision"))
                .andExpect(jsonPath("$.errors[0].reason").value("REQUIRED"));
        putPlan(noRate).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("savingsRateInputPct"))
                .andExpect(jsonPath("$.errors[0].reason").value("REQUIRED"));
        putPlan(noRatio).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("savingsRatioPct"))
                .andExpect(jsonPath("$.errors[0].reason").value("REQUIRED"));
        assertThat(count("plan_version")).isZero();
    }

    @Test
    @DisplayName("비중 83을 보내면 그대로 저장한다 (FR-INP-08, D-085)")
    void storesRequestedSavingsRatio() throws Exception {
        Map<String, Object> body = request();
        body.put("savingsRatioPct", 83);

        JsonNode view = readBody(putPlan(body).andExpect(status().isOk()));

        assertThat(view.get("plan").get("savingsRatioPct").intValue()).isEqualTo(83);
        assertThat(jdbc.sql("SELECT savings_ratio_pct FROM plan_version").query(Integer.class).single()).isEqualTo(83);
    }

    @Test
    @DisplayName("비중이 null이면 NULL로 저장하고 기준 데이터의 기본 비중으로 계산한다 (D-086)")
    void storesNullSavingsRatioAndUsesDefaultRatio() throws Exception {
        JsonNode view = readBody(putPlan(request()).andExpect(status().isOk()));

        assertThat(view.get("plan").get("savingsRatioPct").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT savings_ratio_pct FROM plan_version").query(Integer.class).optional()).isEmpty();
        assertThat(view.get("result").get("assumptions").get("savingsRatioPct").isNull()).isFalse();
    }

    @Test
    @DisplayName("비중이 0~100 밖이면 422 OUT_OF_RANGE(0, 100, PCT)다 (CP-02)")
    void rejectsSavingsRatioOutOfRange() throws Exception {
        Map<String, Object> body = request();
        body.put("savingsRatioPct", 101);

        putPlan(body).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("savingsRatioPct"))
                .andExpect(jsonPath("$.errors[0].reason").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.errors[0].min").value(0))
                .andExpect(jsonPath("$.errors[0].max").value(100))
                .andExpect(jsonPath("$.errors[0].unit").value("PCT"));
        assertThat(count("plan_version")).isZero();
    }

    @Test
    @DisplayName("비중이 정수가 아니면 422 NOT_INTEGER다")
    void rejectsSavingsRatioWithDecimals() throws Exception {
        Map<String, Object> body = request();
        body.put("savingsRatioPct", new BigDecimal("83.5"));

        putPlan(body).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("savingsRatioPct"))
                .andExpect(jsonPath("$.errors[0].reason").value("NOT_INTEGER"));
        assertThat(count("plan_version")).isZero();
    }

    @Test
    @DisplayName("형식이 틀리면 400 MALFORMED_REQUEST다(문자열 revision, 숫자가 아닌 금액, 모르는 목표 종류, 깨진 JSON)")
    void rejectsMalformedRequests() throws Exception {
        Map<String, Object> stringRevision = request();
        stringRevision.put("expectedRevision", "1");
        Map<String, Object> stringAmount = request();
        stringAmount.put("goalAmount", "20000000");
        Map<String, Object> stringRatio = request();
        stringRatio.put("savingsRatioPct", "83");
        Map<String, Object> unknownGoal = request();
        unknownGoal.put("goalType", "YACHT");

        Map<String, Object> numberName = request();
        numberName.put("goalCustomName", 7);
        Map<String, Object> hugeRevision = request();
        hugeRevision.put("expectedRevision", new BigInteger("100000000000000000000"));

        for (Object body : List.of(stringRevision, stringAmount, stringRatio, unknownGoal, numberName,
                hugeRevision, "[]", "{\"goalType\":")) {
            putPlan(body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.errors").isEmpty());
        }
        assertThat(count("plan_version")).isZero();
    }

    @Test
    @DisplayName("300000.5처럼 소수인 금액은 잘리지 않고 422 NOT_INTEGER다")
    void rejectsDecimalAmount() throws Exception {
        String body = JSON.writeValueAsString(request()).replace("300000", "300000.5");

        putPlan(body).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("monthlySavingAmount"))
                .andExpect(jsonPath("$.errors[0].reason").value("NOT_INTEGER"));
    }

    @Test
    @DisplayName("CUSTOM 목표명이 101자면 OUT_OF_RANGE(min 1, max 100, CHAR)이고 field는 요청 키 이름이다")
    void rejectsTooLongCustomName() throws Exception {
        Map<String, Object> custom = request();
        custom.put("goalType", "CUSTOM");
        custom.put("goalCustomName", "가".repeat(101));

        putPlan(custom).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("goalCustomName"))
                .andExpect(jsonPath("$.errors[0].reason").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.errors[0].min").value(1))
                .andExpect(jsonPath("$.errors[0].max").value(100))
                .andExpect(jsonPath("$.errors[0].unit").value("CHAR"));
    }

    @Test
    @DisplayName("revision이 맞지 않으면 409 PLAN_CHANGED이고 errors는 []이며 계획은 바뀌지 않는다")
    void rejectsRevisionMismatch() throws Exception {
        Map<String, Object> withRevision = request();
        withRevision.put("expectedRevision", 1);
        putPlan(withRevision).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_CHANGED"))
                .andExpect(jsonPath("$.errors").isEmpty());

        putPlan(request()).andExpect(status().isOk());
        putPlan(request()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_CHANGED"));
        withRevision.put("expectedRevision", 7);
        putPlan(withRevision).andExpect(status().isConflict());

        assertThat(count("plan_version")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT revision FROM plan").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("두 탭이 같은 expectedRevision으로 거의 동시에 저장하면 한쪽만 성공하고 다른 쪽은 409다")
    void allowsOnlyOneOfConcurrentSaves() throws Exception {
        putPlan(request()).andExpect(status().isOk());
        Map<String, Object> edit = request();
        edit.put("expectedRevision", 1);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> save = () -> {
            start.await();
            return putPlan(edit).andReturn().getResponse().getStatus();
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            Future<Integer> first = executor.submit(save);
            Future<Integer> second = executor.submit(save);
            start.countDown();
            statuses.add(first.get());
            statuses.add(second.get());
        } finally {
            executor.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.sql("SELECT revision FROM plan").query(Long.class).single()).isEqualTo(2L);
        assertThat(count("plan_version")).isEqualTo(2);
    }

    @Test
    @DisplayName("입력 수정은 시작월을 현재 버전 값으로 유지하고, 첫 저장은 요청한 달이다")
    void keepsStartMonthOnEdit() throws Exception {
        JsonNode first = readBody(putPlan(request()).andExpect(status().isOk()));
        assertThat(first.get("result").get("calcStartMonth").asString())
                .isEqualTo(YearMonth.now(ZoneId.of("Asia/Seoul")).toString());
        jdbc.sql("UPDATE plan_version SET calc_start_month = DATE '2026-01-01'").update();
        Map<String, Object> edit = request();
        edit.put("expectedRevision", 1);
        edit.put("monthlySavingAmount", 500_000L);

        JsonNode edited = readBody(putPlan(edit).andExpect(status().isOk()));
        awaitEventsCompleted();

        assertThat(edited.get("result").get("calcStartMonth").asString()).isEqualTo("2026-01");
        assertThat(edited.get("revision").asLong()).isEqualTo(2L);
        // 같은 트랜잭션에서 저장한 새 버전으로 계산했는지: 응답 값, 기록의 계획 버전, 리스너 뒤에도 그 버전 기록 1행
        assertThat(edited.get("plan").get("monthlySavingAmount").asLong()).isEqualTo(500_000L);
        long currentVersionId = jdbc.sql("SELECT current_version_id FROM plan").query(Long.class).single();
        assertThat(jdbc.sql("SELECT plan_version_id FROM calc_run WHERE id = ?")
                .param(edited.get("calcRunId").asLong()).query(Long.class).single()).isEqualTo(currentVersionId);
        assertThat(jdbc.sql("SELECT count(*) FROM calc_run WHERE plan_version_id = ?").param(currentVersionId)
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(edited.get("calcRunId").asLong()).isNotEqualTo(first.get("calcRunId").asLong());
    }

    @Test
    @DisplayName("저장 직후 커밋 뒤 PlanChanged 리스너가 돌아도 calc_run(PLAN)은 응답의 1행뿐이다")
    void keepsSingleCalcRunAfterListener() throws Exception {
        JsonNode body = readBody(putPlan(request()).andExpect(status().isOk()));
        awaitEventsCompleted();

        assertThat(count("calc_run")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT id FROM calc_run").query(Long.class).single())
                .isEqualTo(body.get("calcRunId").asLong());
    }

    @Test
    @DisplayName("로그인하지 않으면 401이다")
    void requiresLogin() throws Exception {
        mockMvc.perform(put("/api/plan").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(request())))
                .andExpect(status().isUnauthorized());
    }
}
