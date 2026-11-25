package com.moeun.backend.calc.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.CalcStatus;
import com.moeun.backend.calc.MonthlyPoint;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionInfo;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.EtfSeries;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * test/resources/golden의 손계산 골든 케이스(plan 종류)를 읽는다.
 * 금액은 원 단위 정수, 금리·세율은 문자열 소수라서 BigDecimal로 읽는다(golden/README.md).
 */
record GoldenCase(String id, CalcInput input, CalcParams params, EtfSeries sp500, Expected expected) {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long TEST_PARAM_VERSION_ID = 1L;
    private static final long TEST_SP500_VERSION_ID = 2L;
    /** 골든 케이스의 대표 종목(S&P500 추종, 세율 15.4%). 종목별 세율은 엔진 Issue(②)에서 쓴다. */
    static final EtfProduct SP500 = new EtfProduct("SP500", "S&P500 추종 ETF", new BigDecimal("15.4"));
    private static final YearMonth FIRST_CLOSE_MONTH = YearMonth.of(2016, 10);
    private static final int DEFAULT_OBSERVED_RETURN_COUNT = 120;
    private static final String DEFAULT_MONTHLY_RETURN = "0.01";

    /**
     * 같은 한 달 수익률이 이어지는 종가. 종가(t) = 100 × (1 + r)^t라서 수익률이 정확히 r로 되돌아온다.
     * 적금만·초과 골든 케이스는 S&P500을 쓰지 않지만 엔진 입력에는 활성 데이터가 늘 들어가므로 같은 모양으로 채운다.
     */
    static EtfSeries createConstantReturnSeries(int returnCount, BigDecimal monthlyReturn) {
        return createConstantReturnSeries(returnCount, monthlyReturn, SP500);
    }

    /** 종목(매매차익 세율)을 정해 만든다. 비과세 종목 골든 케이스(G10)가 쓴다. */
    static EtfSeries createConstantReturnSeries(int returnCount, BigDecimal monthlyReturn, EtfProduct product) {
        DataVersionInfo version = new DataVersionInfo(TEST_SP500_VERSION_ID, DataKind.ETF_PRICE, "golden",
                LocalDate.of(2026, 10, 31), "골든 케이스", product.code());
        BigDecimal growth = BigDecimal.ONE.add(monthlyReturn);
        List<EtfSeries.MonthlyClose> closes = new ArrayList<>();
        BigDecimal close = BigDecimal.valueOf(100);
        for (int index = 0; index <= returnCount; index++) {
            closes.add(new EtfSeries.MonthlyClose(FIRST_CLOSE_MONTH.plusMonths(index), close));
            close = close.multiply(growth);
        }
        return new EtfSeries(version, product, closes);
    }

    /** 레포의 테스트용 가짜 S&P500 121개월(refdata/test-sp500-2016-10-to-2026-10.csv, 실제 값 아님). */
    static EtfSeries readTestEtfSeries() {
        DataVersionInfo version = new DataVersionInfo(TEST_SP500_VERSION_ID, DataKind.ETF_PRICE, "test-sp500",
                LocalDate.of(2026, 10, 31), "테스트용 가짜 데이터", SP500.code());
        List<EtfSeries.MonthlyClose> closes = new ArrayList<>();
        try (InputStream in = new ClassPathResource("refdata/test-sp500-2016-10-to-2026-10.csv").getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String row = line.strip();
                if (row.isEmpty() || row.startsWith("#") || row.startsWith("ym,")) {
                    continue;
                }
                String[] cells = row.split(",");
                closes.add(new EtfSeries.MonthlyClose(YearMonth.parse(cells[0]), new BigDecimal(cells[1])));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read test S&P500 series", exception);
        }
        return new EtfSeries(version, SP500, closes);
    }

    /** 계산 파라미터 한 벌. 세율은 케이스마다 받고 나머지는 seed/calc-param.csv와 같은 값이다. */
    static CalcParams createParams(BigDecimal taxRatePct) {
        DataVersionInfo version = new DataVersionInfo(TEST_PARAM_VERSION_ID, DataKind.CALC_PARAM, "golden",
                LocalDate.of(2026, 11, 17), "골든 케이스");
        return new CalcParams(version, Map.of(
                CalcParamKey.TAX_RATE_PCT, taxRatePct,
                CalcParamKey.DEFAULT_SAVINGS_RATIO_PCT, BigDecimal.valueOf(50),
                CalcParamKey.SCENARIO_OBSERVATION_MONTHS, BigDecimal.valueOf(120),
                CalcParamKey.SCENARIO_LOW_PERCENTILE, BigDecimal.valueOf(10),
                CalcParamKey.SCENARIO_HIGH_PERCENTILE, BigDecimal.valueOf(90),
                CalcParamKey.BOOTSTRAP_PATH_COUNT, BigDecimal.valueOf(10_000),
                CalcParamKey.BOOTSTRAP_SEED, BigDecimal.valueOf(20_261_117)));
    }

    static GoldenCase read(String fileName) {
        return from(readTree(fileName));
    }

    /** component 종류 골든 케이스(G5·G6·G7)를 그대로 읽는다. 엔진 안의 한 단계만 확인하는 입력·기대값이다. */
    static JsonNode readTree(String fileName) {
        try (InputStream in = new ClassPathResource("golden/" + fileName).getInputStream()) {
            return JSON.readTree(in);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read golden case " + fileName, exception);
        }
    }

    /** JSON 배열의 문자열 소수를 BigDecimal 목록으로 읽는다. */
    static List<BigDecimal> readDecimals(JsonNode array) {
        List<BigDecimal> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(new BigDecimal(value.asString()));
        }
        return values;
    }

    private static GoldenCase from(JsonNode root) {
        JsonNode in = root.get("input");
        CalcInput input = new CalcInput(
                in.get("goalAmount").asLong(),
                in.get("targetPeriodMonths").asInt(),
                YearMonth.parse(in.get("calcStartMonth").asString()),
                in.get("heldAssets").asLong(),
                in.get("monthlySavingAmount").asLong(),
                new BigDecimal(in.get("savingsRatePct").asString()),
                in.get("savingsRatioPct").asInt(),
                Scenario.valueOf(in.get("baselineScenario").asString()));
        BigDecimal taxRatePct = new BigDecimal(root.get("params").get("taxRatePct").asString());
        // ETF 매매차익 세율은 종목 정보에서 온다(#150). 따로 적지 않은 케이스는 적금 이자 세율과 같다
        JsonNode etfTax = root.get("params").get("etfGainTaxRatePct");
        EtfProduct product = etfTax == null ? SP500
                : new EtfProduct("TEST-ETF", "테스트 ETF", new BigDecimal(etfTax.asString()));
        // G8·G10은 관측 수익률(120개 모두 같은 값)을 직접 정한다. 나머지는 쓰지 않으므로 기본 모양으로 채운다
        JsonNode observed = in.get("observedMonthlyReturns");
        EtfSeries sp500 = observed == null
                ? createConstantReturnSeries(DEFAULT_OBSERVED_RETURN_COUNT, new BigDecimal(DEFAULT_MONTHLY_RETURN),
                        product)
                : createConstantReturnSeries(observed.get("count").asInt(),
                        new BigDecimal(observed.get("everyValue").asString()), product);
        return new GoldenCase(root.get("id").asString(), input, createParams(taxRatePct), sp500,
                readExpected(root.get("expected")));
    }

    private static Expected readExpected(JsonNode node) {
        List<ExpectedScenario> scenarios = new ArrayList<>();
        for (JsonNode scenario : node.get("scenarios")) {
            List<MonthlyPoint> points = new ArrayList<>();
            for (JsonNode point : scenario.get("monthlyPoints")) {
                points.add(new MonthlyPoint(YearMonth.parse(point.get("month").asString()),
                        point.get("principal").asLong(), point.get("afterTaxAsset").asLong()));
            }
            JsonNode reachMonth = scenario.get("goalReachMonth");
            scenarios.add(new ExpectedScenario(
                    Scenario.valueOf(scenario.get("scenario").asString()),
                    scenario.get("afterTaxAsset").asLong(),
                    scenario.get("principal").asLong(),
                    scenario.get("afterTaxGain").asLong(),
                    scenario.get("gapToGoal").asLong(),
                    scenario.get("isAchieved").asBoolean(),
                    reachMonth.isNull() ? null : YearMonth.parse(reachMonth.asString()),
                    points));
        }
        return new Expected(CalcStatus.valueOf(node.get("status").asString()),
                YearMonth.parse(node.get("targetMonth").asString()), scenarios);
    }

    record Expected(CalcStatus status, YearMonth targetMonth, List<ExpectedScenario> scenarios) {
    }

    record ExpectedScenario(Scenario scenario, long afterTaxAsset, long principal, long afterTaxGain,
            long gapToGoal, boolean isAchieved, YearMonth goalReachMonth, List<MonthlyPoint> monthlyPoints) {
    }
}
