package com.moeun.backend.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.refdata.DataVersionInfo;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.application.ReferenceDataLoader;
import com.moeun.backend.refdata.application.VersionSpec;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * P-4 현재 계획·결과 조회 {@code GET /api/plan}(#31, 계약 C12a-6). 실제 PostgreSQL·로그인 쿠키로 확인한다.
 * S&P500은 레포의 테스트용 가짜 121개월을 쓴다.
 */
@SpringBootTest(properties = {"moeun.demo.accounts=demo01:pw-123456",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class GetPlanApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration EVENT_WAIT = Duration.ofSeconds(20);
    /** 같은 DB를 쓰는 다른 테스트 클래스의 069500 라벨("test-kospi", "test-kospi-chat")과 겹치지 않게 둔다. */
    private static final String KOSPI_LABEL = "test-kospi-get-plan";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CurrentPlanResultService resultService;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ReferenceDataLoader loader;

    Cookie cookie;
    long memberId;

    @BeforeEach
    void login() throws Exception {
        awaitEventsCompleted();
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        cookie = logIn();
        memberId = jdbc.sql("SELECT id FROM member WHERE login_id = 'demo01'").query(Long.class).single();
    }

    private Cookie logIn() throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);
    }

    private static Map<String, Object> request(long heldAssets) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedRevision", null);
        body.put("goalType", "HOUSE");
        body.put("goalCustomName", null);
        body.put("goalAmount", 20_000_000L);
        body.put("targetPeriodMonths", 36);
        body.put("heldAssets", heldAssets);
        body.put("monthlySavingAmount", 300_000L);
        body.put("savingsRateInputPct", null);
        body.put("savingsRatioPct", null);
        return body;
    }

    /** P-3으로 계획을 저장하고 응답을 돌려준다. 저장이 발행한 리스너가 끝날 때까지 기다린다. */
    private JsonNode savePlan(long heldAssets) throws Exception {
        String body = mockMvc.perform(put("/api/plan").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(request(heldAssets))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        awaitEventsCompleted();
        return JSON.readTree(body);
    }

    private JsonNode getPlan(Cookie with) throws Exception {
        return JSON.readTree(mockMvc.perform(get("/api/plan").cookie(with))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
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
    @DisplayName("저장된 계획이 있으면 시작월·입력값·결과가 저장한 그대로 오고, P-3 응답과 같은 calcRunId다")
    void returnsSavedPlanWithSameCalcRunId() throws Exception {
        JsonNode saved = savePlan(5_000_000L);

        JsonNode current = getPlan(cookie);

        assertThat(current.get("revision").asLong()).isEqualTo(saved.get("revision").asLong());
        assertThat(current.get("calcRunId").asLong()).isEqualTo(saved.get("calcRunId").asLong());
        // 입력값은 요청에 보낸 값과 직접 비교한다(응답끼리만 비교하면 같은 변환의 실수를 못 잡는다)
        JsonNode plan = current.get("plan");
        assertThat(plan.get("goalType").asString()).isEqualTo("HOUSE");
        assertThat(plan.get("goalCustomName").isNull()).isTrue();
        assertThat(plan.get("goalAmount").asLong()).isEqualTo(20_000_000L);
        assertThat(plan.get("targetPeriodMonths").asInt()).isEqualTo(36);
        assertThat(plan.get("heldAssets").asLong()).isEqualTo(5_000_000L);
        assertThat(plan.get("monthlySavingAmount").asLong()).isEqualTo(300_000L);
        assertThat(plan.get("savingsRateInputPct").isNull()).isTrue();
        assertThat(plan.get("savingsRatioPct").isNull()).isTrue();
        assertThat(plan.get("etfProductCode").asString()).isEqualTo("SP500");
        assertThat(current.get("result")).isEqualTo(saved.get("result"));
        assertThat(current.get("display")).isEqualTo(saved.get("display"));
        // 결과 화면 배지(#224): 같은 응답의 gapToGoal·isAchieved에서 기대 문자열을 따로 만들어 전체를 비교한다
        for (JsonNode scenario : current.get("result").get("scenarios")) {
            long gap = scenario.get("gapToGoal").asLong();
            String label = scenario.get("isAchieved").asBoolean() ? "달성" : "미달";
            String amount = String.format("%,d만 원", Math.round(Math.abs(gap) / 10_000.0));
            String expected = label + " · 목표 " + (gap < 0 ? "\u2212" : "+") + amount;
            assertThat(current.get("display").get(scenario.get("scenario").asString() + ".gapBadge").asString())
                    .isEqualTo(expected);
        }
        assertThat(current.get("result").get("calcStartMonth").asString())
                .isEqualTo(YearMonth.now(ZoneId.of("Asia/Seoul")).toString());
        assertThat(count("calc_run")).isEqualTo(1);
    }

    @Test
    @DisplayName("계획이 없으면 409 NO_PLAN(problem+json, errors [])이고 계획·시작월을 만들지 않는다")
    void returnsNoPlanWithoutCreatingAnything() throws Exception {
        mockMvc.perform(get("/api/plan").cookie(cookie))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NO_PLAN"))
                .andExpect(jsonPath("$.errors").isEmpty());

        assertThat(count("plan")).isZero();
        assertThat(count("plan_version")).isZero();
        assertThat(count("calc_run")).isZero();
    }

    @Test
    @DisplayName("6개월 전 시작월 계획도 오래된 계획 질문 없이 결과가 온다(D-045)")
    void returnsOldPlanAsIs() throws Exception {
        savePlan(5_000_000L);
        YearMonth sixMonthsAgo = YearMonth.now(ZoneId.of("Asia/Seoul")).minusMonths(6);
        jdbc.sql("UPDATE plan_version SET calc_start_month = ?").param(sixMonthsAgo.atDay(1)).update();

        JsonNode current = getPlan(cookie);

        assertThat(current.get("result").get("calcStartMonth").asString()).isEqualTo(sixMonthsAgo.toString());
        assertThat(current.get("result").get("status").asString()).isEqualTo("CALCULATED");
        assertThat(current.propertyNames()).containsExactlyInAnyOrder("revision", "calcRunId",
                "baselineResultScenario", "undoAvailable", "plan", "result", "display");
    }

    @Test
    @DisplayName("보유 자산 ≥ 목표인 계획이면 ALREADY_EXCEEDED이고 revision·calcRunId는 있다")
    void returnsAlreadyExceededPlan() throws Exception {
        savePlan(25_000_000L);

        JsonNode current = getPlan(cookie);

        assertThat(current.get("result").get("status").asString()).isEqualTo("ALREADY_EXCEEDED");
        assertThat(current.get("baselineResultScenario").isNull()).isTrue();
        assertThat(current.get("revision").asLong()).isEqualTo(1L);
        assertThat(current.get("calcRunId").isNumber()).isTrue();
    }

    @Test
    @DisplayName("응답은 #30과 같은 PlanView이고 제외 필드·문구 키 목록이 없다")
    void exposesSamePlanViewAsSave() throws Exception {
        savePlan(5_000_000L);

        JsonNode current = getPlan(cookie);

        assertThat(current.get("plan").propertyNames()).containsExactlyInAnyOrder("goalType", "goalCustomName",
                "goalAmount", "targetPeriodMonths", "heldAssets", "monthlySavingAmount", "savingsRateInputPct",
                "savingsRatioPct", "etfProductCode");
        assertThat(current.get("result").propertyNames()).containsExactlyInAnyOrder("status", "goalAmount",
                "calcStartMonth", "targetMonth", "scenarios", "principalPoints", "monthlyDetails", "assumptions");
        // undoAvailable은 '직전 계획으로 되돌리기' 버튼에 쓰므로 내보낸다(D-095, #263)
        assertThat(current.toString()).doesNotContain("heldAssetsRecheck", "annualReturnPct", "currentVersionId",
                "previousVersionId", "dataVersions", "memberId", "requiredMonthlySaving");
    }

    @Test
    @DisplayName("monthlyPoints는 엔진이 만든 목록과 점 수·값이 같고 month·afterTaxAsset만 있다")
    void copiesEngineMonthlyPoints() throws Exception {
        savePlan(5_000_000L);

        JsonNode scenarios = getPlan(cookie).get("result").get("scenarios");
        List<ScenarioResult> engineScenarios = transactions.execute(status -> resultService
                .findForCurrentPlan(memberId).orElseThrow()).result().scenarios();

        assertThat(scenarios).hasSize(engineScenarios.size());
        for (int s = 0; s < engineScenarios.size(); s++) {
            JsonNode points = scenarios.get(s).get("monthlyPoints");
            List<MonthlyPoint> enginePoints = engineScenarios.get(s).monthlyPoints();
            assertThat(points).hasSize(enginePoints.size());
            for (int k = 0; k < enginePoints.size(); k++) {
                assertThat(points.get(k).propertyNames()).containsExactlyInAnyOrder("month", "afterTaxAsset");
                assertThat(points.get(k).get("month").asString()).isEqualTo(enginePoints.get(k).month().toString());
                assertThat(points.get(k).get("afterTaxAsset").asLong())
                        .isEqualTo(enginePoints.get(k).afterTaxAsset());
            }
        }
    }

    @Test
    @DisplayName("재로그인해 다시 조회해도 기준 데이터가 그대로면 새로 기록하지 않고 같은 calcRunId다(D-010)")
    void keepsCalcRunIdAcrossRepeatedQueriesAndRelogin() throws Exception {
        JsonNode saved = savePlan(5_000_000L);

        long first = getPlan(cookie).get("calcRunId").asLong();
        long second = getPlan(cookie).get("calcRunId").asLong();
        long afterRelogin = getPlan(logIn()).get("calcRunId").asLong();

        assertThat(List.of(first, second, afterRelogin)).containsOnly(saved.get("calcRunId").asLong());
        assertThat(count("calc_run")).isEqualTo(1);
    }

    @Test
    @DisplayName("현재 버전의 기록이 없으면 조회가 계산·기록하고 그 id를 돌려준다")
    void recordsWhenMissing() throws Exception {
        savePlan(5_000_000L);
        jdbc.sql("DELETE FROM calc_run").update();

        JsonNode current = getPlan(cookie);

        assertThat(count("calc_run")).isEqualTo(1);
        assertThat(current.get("calcRunId").asLong()).isEqualTo(jdbc.sql("SELECT id FROM calc_run")
                .query(Long.class).single());
    }

    @Test
    @DisplayName("입력을 수정해 저장하면 조회는 새 현재 버전(revision 2, 새 입력값, 새 calcRunId)을 준다")
    void returnsLatestVersionAfterEdit() throws Exception {
        JsonNode first = savePlan(5_000_000L);
        Map<String, Object> edit = request(5_000_000L);
        edit.put("expectedRevision", 1);
        edit.put("monthlySavingAmount", 500_000L);
        mockMvc.perform(put("/api/plan").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(edit)))
                .andExpect(status().isOk());
        awaitEventsCompleted();

        JsonNode current = getPlan(cookie);

        assertThat(current.get("revision").asLong()).isEqualTo(2L);
        assertThat(current.get("plan").get("monthlySavingAmount").asLong()).isEqualTo(500_000L);
        assertThat(current.get("calcRunId").asLong()).isNotEqualTo(first.get("calcRunId").asLong());
        long currentVersionId = jdbc.sql("SELECT current_version_id FROM plan").query(Long.class).single();
        assertThat(jdbc.sql("SELECT plan_version_id FROM calc_run WHERE id = ?")
                .param(current.get("calcRunId").asLong()).query(Long.class).single()).isEqualTo(currentVersionId);
    }

    @Test
    @DisplayName("계획의 종목이 069500이면 그 종목의 시계열·세율로 계산하고 응답 plan에도 그 종목이 있다(#154)")
    void calculatesWithEtfProductOfPlan() throws Exception {
        savePlan(5_000_000L);
        long kospi = loadKospi200();
        // 종목을 바꾸는 적용 경로가 아직 없어(#154 2단계) 행을 직접 고치고, 이 버전의 기존 기록을 지워 다시 계산하게 한다
        jdbc.sql("UPDATE plan_version SET etf_product_code = '069500'").update();
        jdbc.sql("DELETE FROM calc_run").update();

        JsonNode current = getPlan(cookie);

        assertThat(current.get("plan").get("etfProductCode").asString()).isEqualTo("069500");
        assertThat(current.get("result").get("assumptions").get("etfProductCode").asString()).isEqualTo("069500");
        assertThat(jdbc.sql("SELECT sp500_version_id FROM calc_run WHERE id = ?")
                .param(current.get("calcRunId").asLong()).query(Long.class).single()).isEqualTo(kospi);
    }

    /** 대표 상품과 같은 가짜 종가에 종목 정보만 069500(비과세)으로 둔 가격 버전 id. 이미 있으면 그 버전을 쓴다. */
    private long loadKospi200() throws Exception {
        try (InputStream csv = new ClassPathResource("refdata/test-sp500-2016-10-to-2026-10.csv").getInputStream()) {
            return loader.loadEtf(new EtfProduct("069500", "코스피200 추종 ETF", BigDecimal.ZERO),
                    new VersionSpec(KOSPI_LABEL, LocalDate.of(2026, 10, 31), "테스트용 가짜 데이터"), csv)
                    .map(DataVersionInfo::id)
                    .orElseGet(() -> jdbc.sql("SELECT id FROM data_version WHERE data_kind = 'ETF_PRICE' "
                            + "AND product_code = '069500' AND version_label = ?").param(KOSPI_LABEL)
                            .query(Long.class).single());
        }
    }

    @Test
    @DisplayName("로그인하지 않으면 401 UNAUTHENTICATED(errors [])다")
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/api/plan"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }
}
