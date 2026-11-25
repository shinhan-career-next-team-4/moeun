package com.moeun.backend.calc.web;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.ResultActions;

import com.moeun.backend.calc.ChatComparisonQuery;
import com.moeun.backend.calc.ChatComparisonView;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.application.ReferenceDataLoader;
import com.moeun.backend.refdata.application.VersionSpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대화 조건 누적(#418, D-103)의 비교 기준 {@code baselineConditionChanges}. current는 기준 계획에 비교 기준 변경을 얹은 계획,
 * changed는 거기에 이번 변경을 더 얹은 계획이다. diff·display는 모두 이 두 쪽을 계산 엔진이 비교한 값이다.
 */
@SpringBootTest(properties = {"moeun.ai-server.internal-api-key=test-internal-key",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class InternalCalcBaselineTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "test-internal-key";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration EVENT_WAIT = Duration.ofSeconds(20);
    /** 같은 DB를 쓰는 다른 테스트 클래스의 069500 라벨과 겹치지 않게 둔다. */
    private static final String KOSPI_LABEL = "test-kospi-internal-calc-baseline";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlanSaveService planSaveService;

    @Autowired
    ReferenceDataLoader loader;

    @Autowired
    ChatComparisonQuery chatComparisons;

    long memberId;

    @BeforeEach
    void setUp() throws Exception {
        awaitEventsCompleted();
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        jdbc.sql("DELETE FROM member WHERE login_id = 'internal-calc-baseline'").update();
        memberId = jdbc.sql("INSERT INTO member (login_id, password_hash) VALUES ('internal-calc-baseline', 'x') "
                + "RETURNING id").query(Long.class).single();
        try (InputStream csv = new ClassPathResource("refdata/test-sp500-2016-10-to-2026-10.csv").getInputStream()) {
            loader.loadEtf(new EtfProduct("069500", "코스피200 추종 ETF", BigDecimal.ZERO),
                    new VersionSpec(KOSPI_LABEL, LocalDate.of(2026, 10, 31), "테스트용 가짜 데이터"), csv);
        }
        // 목표 2,000만원, 36개월, 보유 500만원, 월 30만원, 기본금리, 비중 5:5, 종목 S&P500
        planSaveService.save(memberId, valid().goalAmount(20_000_000L).targetPeriodMonths(36).heldAssets(5_000_000L)
                .monthlySavingAmount(300_000L).build(), null);
        awaitEventsCompleted();
    }

    private void awaitEventsCompleted() {
        await().atMost(EVENT_WAIT).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }

    private ResultActions postCalc(Map<String, Object> baseline, Map<String, Object> changes) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberId", memberId);
        if (baseline != null) {
            body.put("baselineConditionChanges", baseline);
        }
        body.put("conditionChanges", changes);
        return mockMvc.perform(post("/internal/calc").header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private JsonNode calc(Map<String, Object> baseline, Map<String, Object> changes) throws Exception {
        ResultActions actions = postCalc(baseline, changes).andExpect(status().isOk());
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static Map<String, Object> changes(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            map.put((String)keyValues[index], keyValues[index + 1]);
        }
        return map;
    }

    private static long asset(JsonNode side, String scenario) {
        for (JsonNode item : side.get("scenarios")) {
            if (item.get("scenario").asString().equals(scenario)) {
                return item.get("afterTaxAsset").asLong();
            }
        }
        throw new AssertionError("scenario " + scenario + " is missing");
    }

    @Test
    @DisplayName("비중을 30으로 바꾼 뒤 월 150만원을 물으면 current는 30:70 계획, changed는 30:70 + 월 150만원이다")
    void keepsBaselineChangesOnBothSides() throws Exception {
        JsonNode body = calc(changes("savingsRatioPct", 30), changes("monthlySavingAmount", 1_500_000));

        JsonNode current = body.get("conditions").get("current");
        JsonNode changed = body.get("conditions").get("changed");
        assertThat(current.get("savingsRatioPct").asInt()).isEqualTo(30);
        assertThat(current.get("monthlySavingAmount").asLong()).isEqualTo(300_000L);
        assertThat(changed.get("savingsRatioPct").asInt()).isEqualTo(30);
        assertThat(changed.get("monthlySavingAmount").asLong()).isEqualTo(1_500_000L);
        assertThat(body.get("display").get("conditions.current.savingsRatioPct").asString())
                .isEqualTo("적금 30 : ETF 70");
    }

    @Test
    @DisplayName("current는 비교 기준만 바꾼 계산, changed는 두 변경을 함께 바꾼 계산과 결과가 같다")
    void matchesSeparateCalculations() throws Exception {
        JsonNode accumulated = calc(changes("savingsRatioPct", 30), changes("monthlySavingAmount", 1_500_000));
        JsonNode baselineOnly = calc(null, changes("savingsRatioPct", 30));
        JsonNode both = calc(null, changes("savingsRatioPct", 30, "monthlySavingAmount", 1_500_000));

        for (String scenario : new String[] {"GOOD", "AVG", "BAD"}) {
            assertThat(asset(accumulated.get("current"), scenario))
                    .isEqualTo(asset(baselineOnly.get("changed"), scenario));
            assertThat(asset(accumulated.get("changed"), scenario)).isEqualTo(asset(both.get("changed"), scenario));
        }
        // 차이는 직전 누적 계획(current) 대비다
        JsonNode avgDiff = accumulated.get("diff").get("byScenario").get(1);
        assertThat(avgDiff.get("afterTaxAssetDelta").asLong())
                .isEqualTo(asset(accumulated.get("changed"), "AVG") - asset(accumulated.get("current"), "AVG"));
    }

    @Test
    @DisplayName("같은 조건을 두 번 바꾸면(150만 → 200만) current는 150만, changed는 200만이다")
    void laterChangeWinsForSameKey() throws Exception {
        JsonNode body = calc(changes("monthlySavingAmount", 1_500_000), changes("monthlySavingAmount", 2_000_000));

        assertThat(body.get("conditions").get("current").get("monthlySavingAmount").asLong()).isEqualTo(1_500_000L);
        assertThat(body.get("conditions").get("changed").get("monthlySavingAmount").asLong()).isEqualTo(2_000_000L);
    }

    @Test
    @DisplayName("비교 기준에서 바꾼 종목은 current 종목이 되고, 이번에 다른 조건만 바꾸면 changed도 그 종목이다")
    void baselineEtfProductBecomesCurrentProduct() throws Exception {
        JsonNode body = calc(changes("etfProductCode", "069500"), changes("monthlySavingAmount", 500_000));

        assertThat(body.get("current").get("assumptions").get("etfProductCode").asString()).isEqualTo("069500");
        assertThat(body.get("changed").get("assumptions").get("etfProductCode").asString()).isEqualTo("069500");
    }

    @Test
    @DisplayName("비교 기준이 비었거나 null이면 지금처럼 기준 계획과 비교한다")
    void emptyBaselineKeepsPlanAsCurrent() throws Exception {
        JsonNode body = calc(changes(), changes("monthlySavingAmount", 500_000));

        assertThat(body.get("conditions").get("current").get("monthlySavingAmount").asLong()).isEqualTo(300_000L);
        assertThat(body.get("conditions").get("current").get("savingsRatioPct").asInt()).isEqualTo(50);
    }

    @Test
    @DisplayName("비교 기준 값이 범위를 벗어나면 422 VALIDATION_FAILED이고 field는 baselineConditionChanges.<키>다")
    void rejectsOutOfRangeBaseline() throws Exception {
        postCalc(changes("savingsRatioPct", 150), changes("monthlySavingAmount", 500_000))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("baselineConditionChanges.savingsRatioPct"));
    }

    @Test
    @DisplayName("이번 변경과 비교 기준이 모두 범위를 벗어나면 이번 변경의 오류 뒤에 비교 기준의 오류를 담는다")
    void ordersChangeErrorsBeforeBaselineErrors() throws Exception {
        postCalc(changes("savingsRatioPct", 150), changes("monthlySavingAmount", -1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("monthlySavingAmount"))
                .andExpect(jsonPath("$.errors[1].field").value("baselineConditionChanges.savingsRatioPct"));
    }

    @Test
    @DisplayName("비교 기준에 바꿀 수 없는 키가 있으면 422 FIELD_NOT_CHANGEABLE이다")
    void rejectsUnchangeableBaselineKey() throws Exception {
        postCalc(changes("heldAssets", 1_000_000), changes("monthlySavingAmount", 500_000))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_CHANGEABLE"))
                .andExpect(jsonPath("$.errors[0].field").value("baselineConditionChanges.heldAssets"));
    }

    @Test
    @DisplayName("비교 기준이 객체가 아니면 400이다")
    void rejectsNonObjectBaseline() throws Exception {
        mockMvc.perform(post("/internal/calc").header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":" + memberId + ",\"baselineConditionChanges\":[1],"
                        + "\"conditionChanges\":{\"monthlySavingAmount\":500000}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("기록을 다시 계산할 때 같은 비교 기준을 주면 응답과 같은 비교가 나온다(F-4)")
    void recomputesWithBaseline() throws Exception {
        JsonNode body = calc(changes("savingsRatioPct", 30), changes("monthlySavingAmount", 1_500_000));

        ChatComparisonView view = chatComparisons.recompute(memberId, body.get("calcRunId").asLong(),
                Map.of("monthlySavingAmount", JSON.readTree("1500000")),
                Map.of("savingsRatioPct", JSON.readTree("30"))).orElseThrow();

        assertThat(view.comparison().conditions().current().savingsRatioPct()).isEqualTo(30);
        assertThat(view.comparison().changed().scenarios().get(1).afterTaxAsset())
                .isEqualTo(asset(body.get("changed"), "AVG"));
        assertThat(view.display()).containsEntry("conditions.current.savingsRatioPct", "적금 30 : ETF 70");
    }
}
