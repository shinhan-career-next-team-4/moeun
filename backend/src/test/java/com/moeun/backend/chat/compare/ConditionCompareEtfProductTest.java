package com.moeun.backend.chat.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.application.ReferenceDataLoader;
import com.moeun.backend.refdata.application.VersionSpec;

import tools.jackson.databind.JsonNode;

/**
 * 결과 화면에서 종목을 바꾸기 전 미리보기(#154). {@code POST /api/compare}에 {@code etfProductCode}를 주면
 * current는 계획의 종목으로, changed는 바꾼 종목으로 계산한다. 내 계획은 바뀌지 않는다.
 */
class ConditionCompareEtfProductTest extends ConditionCompareTestSupport {

    /** 같은 DB를 쓰는 다른 테스트 클래스의 069500 라벨과 겹치지 않게 둔다. */
    private static final String KOSPI_LABEL = "test-kospi-compare";

    @Autowired
    ReferenceDataLoader loader;

    /** 대표 상품과 같은 가짜 종가에 종목 정보만 069500(비과세)으로 둔 가격 버전. 이미 있으면 그 버전을 쓴다. */
    @BeforeEach
    void loadKospi200() throws Exception {
        try (InputStream csv = new ClassPathResource("refdata/test-sp500-2016-10-to-2026-10.csv").getInputStream()) {
            loader.loadEtf(new EtfProduct("069500", "코스피200 추종 ETF", BigDecimal.ZERO),
                    new VersionSpec(KOSPI_LABEL, LocalDate.of(2026, 10, 31), "테스트용 가짜 데이터"), csv);
        }
    }

    @Test
    @DisplayName("종목만 바꿔 비교하면 current는 S&P500, changed는 코스피200으로 계산하고 내 계획은 그대로다")
    void previewsChangedEtfProduct() throws Exception {
        savePlan(300_000L);
        long versions = count("SELECT count(*) FROM plan_version");

        JsonNode response = ok("{\"etfProductCode\":\"069500\"}");

        assertThat(response.get("changedKeys").get(0).asString()).isEqualTo("etfProductCode");
        assertThat(response.get("changedKeys")).hasSize(1);
        assertThat(response.get("current").get("assumptions").get("etfProductCode").asString()).isEqualTo("SP500");
        assertThat(response.get("changed").get("assumptions").get("etfProductCode").asString()).isEqualTo("069500");
        // 같은 가짜 종가에 세율만 0%라 AVG 구간의 세후 수익이 current보다 크다
        JsonNode avg = findScenario(response.get("diff").get("byScenario"), "AVG");
        assertThat(avg.get("afterTaxGainDelta").asLong()).isPositive();
        assertThat(count("SELECT count(*) FROM plan_version")).isEqualTo(versions);
        assertThat(count("SELECT count(*) FROM calc_run WHERE run_type = 'CHAT'")).isZero();
    }

    private static JsonNode findScenario(JsonNode byScenario, String scenario) {
        for (JsonNode item : byScenario) {
            if (scenario.equals(item.get("scenario").asString())) {
                return item;
            }
        }
        throw new AssertionError("no scenario " + scenario);
    }

    @Test
    @DisplayName("종목과 월 저축액을 함께 바꿔 비교할 수 있다")
    void previewsEtfProductWithOtherChange() throws Exception {
        savePlan(300_000L);

        JsonNode response = ok("{\"etfProductCode\":\"069500\",\"monthlySavingAmount\":400000}");

        assertThat(response.get("changed").get("assumptions").get("etfProductCode").asString()).isEqualTo("069500");
        assertThat(response.get("conditions").get("changed").get("monthlySavingAmount").asLong())
                .isEqualTo(400_000L);
    }

    @Test
    @DisplayName("데이터를 적재하지 않은 종목이면 422 VALIDATION_FAILED, field etfProductCode, reason NOT_AVAILABLE이다")
    void rejectsEtfProductWithoutData() throws Exception {
        savePlan(300_000L);

        JsonNode problem = problem("{\"conditionChanges\":{\"etfProductCode\":\"999999\"}}", 422);

        assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("etfProductCode");
        assertThat(problem.get("errors").get(0).get("reason").asString()).isEqualTo("NOT_AVAILABLE");
    }

    @Test
    @DisplayName("계획의 종목이 069500이면 current도 그 종목으로 계산한다")
    void currentUsesEtfProductOfPlan() throws Exception {
        savePlan(300_000L);
        jdbc.sql("UPDATE plan_version SET etf_product_code = '069500'").update();   // 준비 단계에서만 직접 고친다

        JsonNode response = ok("{\"monthlySavingAmount\":400000}");

        assertThat(response.get("current").get("assumptions").get("etfProductCode").asString()).isEqualTo("069500");
        assertThat(response.get("changed").get("assumptions").get("etfProductCode").asString()).isEqualTo("069500");
    }
}
