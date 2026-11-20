package com.moeun.backend.calc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * 가정 보기(FR-RES-06). 계산에 쓴 기준과 반영하지 않은 항목이다.
 *
 * @param savingsRatePct 계산에 쓴 적금 금리(연, 세전, %)
 * @param isDefaultSavingsRate 기본금리(은행연합회 전월 평균금리)를 썼는지
 * @param savingsRateBaseMonth 기본금리의 기준월. null = 사용자가 넣은 금리를 씀
 * @param savingsRatioPct 적금 비중(0~100 정수). ETF 비중은 100에서 뺀 값
 * @param taxRatePct 적금 이자에 적용한 세율(%). ETF 매매차익 세율은 {@code etfGainTaxRatePct}(#150)
 * @param scenarioMethod 3구간 산정 방식. null = ETF 비중 0%
 * @param sp500BaseDate S&P500 데이터 기준일. null = ETF 비중 0%
 * @param excludedItems 반영하지 않은 항목
 * @param etfProductCode 계산에 쓴 ETF 종목 코드(#150). null = ETF 비중 0%
 * @param etfProductName 그 종목의 화면 이름(상품명 대신 추종 지수, CP-06). null = ETF 비중 0%
 * @param etfGainTaxRatePct 그 종목의 매매차익 세율(%). 국내 주식형 ETF는 0. null = ETF 비중 0%
 */
public record Assumptions(
        BigDecimal savingsRatePct,
        boolean isDefaultSavingsRate,
        YearMonth savingsRateBaseMonth,
        int savingsRatioPct,
        BigDecimal taxRatePct,
        ScenarioMethod scenarioMethod,
        LocalDate sp500BaseDate,
        List<ExcludedItem> excludedItems,
        String etfProductCode,
        String etfProductName,
        BigDecimal etfGainTaxRatePct) {

    public Assumptions {
        Objects.requireNonNull(savingsRatePct, "savingsRatePct");
        Objects.requireNonNull(taxRatePct, "taxRatePct");
        excludedItems = List.copyOf(excludedItems);
    }

    /** ETF 종목 정보가 없는 가정(ETF 비중 0%이거나 종목을 다루지 않는 테스트). */
    public Assumptions(BigDecimal savingsRatePct, boolean isDefaultSavingsRate, YearMonth savingsRateBaseMonth,
            int savingsRatioPct, BigDecimal taxRatePct, ScenarioMethod scenarioMethod, LocalDate sp500BaseDate,
            List<ExcludedItem> excludedItems) {
        this(savingsRatePct, isDefaultSavingsRate, savingsRateBaseMonth, savingsRatioPct, taxRatePct, scenarioMethod,
                sp500BaseDate, excludedItems, null, null, null);
    }
}
