package com.moeun.backend.calc.web;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
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

import com.moeun.backend.calc.CalcResultView;
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
 * 대화로 ETF 종목을 바꾸는 비교(#157, D-097, 계약 internal-calc C2-2·C2-3·C2-6·C2-7). current는 계획의 종목(S&P500),
 * changed는 바꾼 종목으로 계산한다. 069500은 대표 상품과 같은 가짜 종가에 세율만 0%로 둔 테스트 데이터다.
 */
@SpringBootTest(properties = {"moeun.ai-server.internal-api-key=test-internal-key",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class InternalCalcEtfProductTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "test-internal-key";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration EVENT_WAIT = Duration.ofSeconds(20);
    /** 같은 DB를 쓰는 다른 테스트 클래스의 069500 라벨과 겹치지 않게 둔다. */
    private static final String KOSPI_LABEL = "test-kospi-internal-calc";
    private static final String KOSPI_NAME = "코스피200 추종 ETF";

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
        jdbc.sql("DELETE FROM member WHERE login_id = 'internal-calc-etf'").update();
        memberId = jdbc.sql("INSERT INTO member (login_id, password_hash) VALUES ('internal-calc-etf', 'x') "
                + "RETURNING id").query(Long.class).single();
        try (InputStream csv = new ClassPathResource("refdata/test-sp500-2016-10-to-2026-10.csv").getInputStream()) {
            loader.loadEtf(new EtfProduct("069500", KOSPI_NAME, BigDecimal.ZERO),
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

    private ResultActions postChanges(String changesJson) throws Exception {
        return mockMvc.perform(post("/internal/calc").header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":" + memberId + ",\"conditionChanges\":" + changesJson + "}"));
    }

    private static JsonNode readBody(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    /** 응답 본문과 같은 방식으로 읽은 JSON. 숫자 노드 종류(int·long·소수 자릿수)를 맞춰 비교한다. */
    private static JsonNode tree(Object value) {
        return JSON.readTree(JSON.writeValueAsString(value));
    }

    private Long changedEtfVersionOf(long calcRunId) {
        return jdbc.sql("SELECT changed_etf_version_id FROM calc_run WHERE id = ?").param(calcRunId)
                .query((rs, row) -> rs.getObject(1, Long.class)).list().get(0);
    }

    private long countChatRuns() {
        return jdbc.sql("SELECT count(*) FROM calc_run WHERE run_type = 'CHAT'").query(Long.class).single();
    }

    @Test
    @DisplayName("종목만 바꾸면 current는 계획의 종목, changed는 069500으로 계산하고 conditions·display에 종목이 있다")
    void comparesChangedEtfProduct() throws Exception {
        JsonNode body = readBody(postChanges("{\"etfProductCode\":\"069500\"}").andExpect(status().isOk()));

        assertThat(body.get("conditions").get("current").get("etfProductCode").asString()).isEqualTo("SP500");
        assertThat(body.get("conditions").get("changed").get("etfProductCode").asString()).isEqualTo("069500");
        assertThat(body.get("current").get("assumptions").get("etfProductCode").asString()).isEqualTo("SP500");
        assertThat(body.get("changed").get("assumptions").get("etfProductCode").asString()).isEqualTo("069500");
        JsonNode display = body.get("display");
        assertThat(display.get("conditions.current.etfProductName").asString())
                .isEqualTo(body.get("current").get("assumptions").get("etfProductName").asString());
        assertThat(display.get("conditions.changed.etfProductName").asString()).isEqualTo(KOSPI_NAME);
        // 같은 가짜 종가에 세율만 0%라 changed의 세후 수익이 크다
        assertThat(body.get("diff").get("byScenario").get(1).get("afterTaxGainDelta").asLong()).isPositive();
        // C2-7: 두 종목의 가격 버전이 모두 남는다. JSON 키는 그대로이고 changed 쪽 값이 changed 종목의 버전이다
        long changedVersion = body.get("changed").get("dataVersions").get("sp500VersionId").asLong();
        assertThat(changedVersion)
                .isNotEqualTo(body.get("current").get("dataVersions").get("sp500VersionId").asLong());
        assertThat(changedEtfVersionOf(body.get("calcRunId").asLong())).isEqualTo(changedVersion);
    }

    @Test
    @DisplayName("종목과 월 저축액을 함께 바꾸면 둘 다 반영한다")
    void comparesEtfProductWithOtherChange() throws Exception {
        JsonNode body = readBody(postChanges("{\"etfProductCode\":\"069500\",\"monthlySavingAmount\":500000}")
                .andExpect(status().isOk()));

        assertThat(body.get("conditions").get("changed").get("monthlySavingAmount").asLong()).isEqualTo(500_000L);
        assertThat(body.get("conditions").get("changed").get("etfProductCode").asString()).isEqualTo("069500");
        assertThat(body.get("display").get("conditions.changed.monthlySavingAmount").asString()).isEqualTo("약 50만원");
    }

    @Test
    @DisplayName("(예외) 없는 종목 코드면 422 VALIDATION_FAILED, field etfProductCode, reason OUT_OF_RANGE(min·max 없음)이고 기록이 없다")
    void rejectsUnknownEtfProduct() throws Exception {
        JsonNode problem = readBody(postChanges("{\"etfProductCode\":\"999999\"}")
                .andExpect(status().isUnprocessableContent()));

        assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        JsonNode error = problem.get("errors").get(0);
        assertThat(error.get("field").asString()).isEqualTo("etfProductCode");
        assertThat(error.get("reason").asString()).isEqualTo("OUT_OF_RANGE");
        assertThat(error.has("min")).isFalse();
        assertThat(error.has("minDisplay")).isFalse();
        assertThat(countChatRuns()).isZero();
    }

    @Test
    @DisplayName("(예외) 없는 종목과 범위 밖 금액이 함께 오면 걸린 키를 모두 요청 순서로 담는다")
    void listsEtfProductErrorWithOtherErrors() throws Exception {
        JsonNode problem = readBody(postChanges("{\"monthlySavingAmount\":12000000,\"etfProductCode\":\"999999\"}")
                .andExpect(status().isUnprocessableContent()));

        assertThat(problem.get("errors")).hasSize(2);
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("monthlySavingAmount");
        assertThat(problem.get("errors").get(1).get("field").asString()).isEqualTo("etfProductCode");
    }

    @Test
    @DisplayName("(예외) 종목 코드가 문자열이 아니면 400 MALFORMED_REQUEST이고 기록이 없다")
    void rejectsNonStringEtfProduct() throws Exception {
        JsonNode problem = readBody(postChanges("{\"etfProductCode\":133690}").andExpect(status().isBadRequest()));

        assertThat(problem.get("code").asString()).isEqualTo("MALFORMED_REQUEST");
        assertThat(countChatRuns()).isZero();
    }

    @Test
    @DisplayName("계획과 같은 종목을 보내면 바꾸지 않은 것으로 보고 종목 키와 changed 종목 버전을 남기지 않는다")
    void treatsSameEtfProductAsUnchanged() throws Exception {
        JsonNode body = readBody(postChanges("{\"etfProductCode\":\"SP500\",\"monthlySavingAmount\":500000}")
                .andExpect(status().isOk()));

        assertThat(body.get("conditions").get("changed").has("etfProductCode")).isFalse();
        assertThat(body.get("display").has("conditions.changed.etfProductName")).isFalse();
        assertThat(changedEtfVersionOf(body.get("calcRunId").asLong())).isNull();
    }

    @Test
    @DisplayName("changed의 ETF 비중이 0%여도 거절하지 않고 종목 이름을 싣는다(종목 차이는 결과에 없음)")
    void comparesEtfProductWithZeroEtfRatio() throws Exception {
        JsonNode body = readBody(postChanges("{\"etfProductCode\":\"069500\",\"savingsRatioPct\":100}")
                .andExpect(status().isOk()));

        assertThat(body.get("display").get("conditions.changed.etfProductName").asString()).isEqualTo(KOSPI_NAME);
        assertThat(body.get("changed").get("scenarios")).hasSize(1);
    }

    @Test
    @DisplayName("종목을 바꾼 CHAT 기록을 recompute로 다시 계산하면 그때와 display가 같다")
    void recomputesEtfProductComparison() throws Exception {
        assertRecomputeMatches("{\"etfProductCode\":\"069500\",\"monthlySavingAmount\":500000}",
                Map.of("etfProductCode", JSON.readTree("\"069500\""), "monthlySavingAmount",
                        JSON.readTree("500000")));
    }

    @Test
    @DisplayName("ETF 0%로 종목을 바꾼 기록도 recompute로 다시 계산하면 그때와 display가 같다")
    void recomputesEtfProductComparisonWithZeroEtfRatio() throws Exception {
        assertRecomputeMatches("{\"etfProductCode\":\"069500\",\"savingsRatioPct\":100}",
                Map.of("etfProductCode", JSON.readTree("\"069500\""), "savingsRatioPct", JSON.readTree("100")));
    }

    private void assertRecomputeMatches(String changesJson, Map<String, JsonNode> storedChanges) throws Exception {
        assertRecomputeMatches(readBody(postChanges(changesJson).andExpect(status().isOk())), storedChanges);
    }

    private void assertRecomputeMatches(JsonNode body, Map<String, JsonNode> storedChanges) {
        ChatComparisonView view = chatComparisons.recompute(memberId, body.get("calcRunId").asLong(), storedChanges)
                .orElseThrow();

        // 반올림한 display만이 아니라 원 단위 결과·차이·조건·데이터 버전까지 그때와 같아야 한다
        assertThat(view.display()).isEqualTo(JSON.convertValue(body.get("display"), Map.class));
        assertThat(tree(CalcResultView.from(view.comparison().current()))).isEqualTo(body.get("current"));
        assertThat(tree(CalcResultView.from(view.comparison().changed()))).isEqualTo(body.get("changed"));
        assertThat(tree(view.comparison().diff())).isEqualTo(body.get("diff"));
        assertThat(tree(view.comparison().conditions())).isEqualTo(body.get("conditions"));
    }

    @Test
    @DisplayName("계획이 ETF 0%이고 대화로 종목과 비중을 바꾸면 current 칸은 계획 종목, changed 칸은 바꾼 종목의 버전이다")
    void recordsBothVersionsWhenPlanHasNoEtf() throws Exception {
        // 준비 단계에서만 직접 고친다(계획 비중 적금 100)
        jdbc.sql("UPDATE plan_version SET savings_ratio_pct = 100").update();
        long sp500Version = jdbc.sql("SELECT id FROM data_version WHERE data_kind = 'ETF_PRICE' AND is_active "
                + "AND product_code = 'SP500'").query(Long.class).single();

        JsonNode body = readBody(postChanges("{\"etfProductCode\":\"069500\",\"savingsRatioPct\":50}")
                .andExpect(status().isOk()));

        long calcRunId = body.get("calcRunId").asLong();
        assertThat(jdbc.sql("SELECT sp500_version_id FROM calc_run WHERE id = ?").param(calcRunId)
                .query(Long.class).single()).isEqualTo(sp500Version);
        assertThat(changedEtfVersionOf(calcRunId))
                .isEqualTo(body.get("changed").get("dataVersions").get("sp500VersionId").asLong());
        assertThat(body.get("display").get("conditions.changed.etfProductName").asString()).isEqualTo(KOSPI_NAME);
        assertRecomputeMatches(body, Map.of("etfProductCode", JSON.readTree("\"069500\""), "savingsRatioPct",
                JSON.readTree("50")));
    }
}
