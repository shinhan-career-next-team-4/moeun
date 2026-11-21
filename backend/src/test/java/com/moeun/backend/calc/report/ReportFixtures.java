package com.moeun.backend.calc.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionInfo;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.EtfSeries;

/** 리포트 테스트용 가짜 기준 데이터. 실제 가격이 아니다. */
final class ReportFixtures {

    private static final YearMonth FIRST_MONTH = YearMonth.of(2016, 10);
    private static final int DEFAULT_RETURN_COUNT = 120;

    private ReportFixtures() {
    }

    /** 종가 121개(수익률 120개). index 0이 가장 오래된 달이다. */
    static EtfSeries createSeries(String code, String displayName, BigDecimal taxPct, IntFunction<BigDecimal> closeAt) {
        return createSeries(code, displayName, taxPct, DEFAULT_RETURN_COUNT, closeAt);
    }

    static EtfSeries createSeries(String code, String displayName, BigDecimal taxPct, int returnCount,
            IntFunction<BigDecimal> closeAt) {
        EtfProduct product = new EtfProduct(code, displayName, taxPct);
        DataVersionInfo version = new DataVersionInfo(10L, DataKind.ETF_PRICE, "test-" + code,
                LocalDate.of(2026, 10, 31), "테스트용 가짜 데이터", code);
        List<EtfSeries.MonthlyClose> closes = new ArrayList<>(returnCount + 1);
        for (int index = 0; index <= returnCount; index++) {
            closes.add(new EtfSeries.MonthlyClose(FIRST_MONTH.plusMonths(index), closeAt.apply(index)));
        }
        return new EtfSeries(version, product, closes);
    }

    /** seed/calc-param.csv와 같은 값에 경로 수만 줄인 파라미터(테스트를 빠르게). */
    static CalcParams createParams(int pathCount) {
        DataVersionInfo version = new DataVersionInfo(1L, DataKind.CALC_PARAM, "test", LocalDate.of(2026, 11, 17),
                "테스트");
        return new CalcParams(version, Map.of(
                CalcParamKey.TAX_RATE_PCT, new BigDecimal("15.4"),
                CalcParamKey.DEFAULT_SAVINGS_RATIO_PCT, BigDecimal.valueOf(50),
                CalcParamKey.SCENARIO_OBSERVATION_MONTHS, BigDecimal.valueOf(120),
                CalcParamKey.SCENARIO_LOW_PERCENTILE, BigDecimal.valueOf(10),
                CalcParamKey.SCENARIO_HIGH_PERCENTILE, BigDecimal.valueOf(90),
                CalcParamKey.BOOTSTRAP_PATH_COUNT, BigDecimal.valueOf(pathCount),
                CalcParamKey.BOOTSTRAP_SEED, BigDecimal.valueOf(20_261_117)));
    }
}
