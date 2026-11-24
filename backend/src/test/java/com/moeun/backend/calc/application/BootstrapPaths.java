package com.moeun.backend.calc.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.EtfSeries;

/**
 * 엔진이 3구간을 고르기 전의 부트스트랩 경로별 최종 평가액을 다시 만든다(#302, 발표용 분포 그래프).
 * <p>운영 코드({@link CalcEngine}, {@link BootstrapScenarios})는 고치지 않는다. 같은 계산 클래스
 * ({@link SavingsCalculator}, {@link EtfPath}, {@link MonthlyReturns})와 같은 시드·같은 추출 순서(경로 1의 1 … N개월,
 * 경로 2의 1 … N개월 …)를 써서 엔진이 만든 경로와 같은 값을 얻는다. 같은지는 {@link Result}의 3구간이 엔진 결과와
 * 원 단위까지 같은지로 테스트가 확인한다.
 * <p>테스트 소스에만 있다. 엔진 내부 클래스가 package-private이라 같은 패키지에 둔다.
 */
public final class BootstrapPaths {

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    private BootstrapPaths() {
    }

    /**
     * ETF가 든 계획의 경로별 최종(k = N) 총 평가액.
     *
     * @param input 엔진 입력(금리·비중까지 채운 값). ETF 비중이 0보다 커야 한다
     * @param params 계산 파라미터(세율, 관측 기간, 분위, 경로 수, 시드)
     * @param series ETF 종목 시계열
     */
    public static Result simulate(CalcInput input, CalcParams params, EtfSeries series) {
        if (!input.hasEtf() || input.hasExceededGoal()) {
            throw new IllegalArgumentException("bootstrap paths need an ETF plan below its goal");
        }
        int months = input.targetPeriodMonths();
        BigDecimal monthlySaving = BigDecimal.valueOf(input.monthlySavingAmount());
        BigDecimal savingsDeposit = monthlySaving.multiply(BigDecimal.valueOf(input.savingsRatioPct()))
                .divide(PERCENT, MathContext.DECIMAL128);
        SavingsCalculator savings = new SavingsCalculator(savingsDeposit, input.savingsRatePct(),
                params.getValue(CalcParamKey.TAX_RATE_PCT));
        EtfPath etf = new EtfPath(monthlySaving.subtract(savingsDeposit), series.product().gainTaxRatePct());
        List<BigDecimal> observed = MonthlyReturns.fromCloses(series.monthlyCloses(),
                params.getValue(CalcParamKey.SCENARIO_OBSERVATION_MONTHS).intValueExact());
        int pathCount = params.getValue(CalcParamKey.BOOTSTRAP_PATH_COUNT).intValueExact();
        Random random = new Random(params.getValue(CalcParamKey.BOOTSTRAP_SEED).longValueExact());

        BigDecimal fixedPart = BigDecimal.valueOf(input.heldAssets()).add(savings.valueAt(months));
        List<BigDecimal> finals = new ArrayList<>(pathCount);
        for (int path = 0; path < pathCount; path++) {
            BigDecimal etfValue = BigDecimal.ZERO;
            for (int month = 0; month < months; month++) {
                etfValue = etf.grow(etfValue, observed.get(random.nextInt(observed.size())));
            }
            finals.add(fixedPart.add(etf.afterTax(etfValue, months)));
        }
        return new Result(finals, toFraction(params.getValue(CalcParamKey.SCENARIO_LOW_PERCENTILE)),
                toFraction(params.getValue(CalcParamKey.SCENARIO_HIGH_PERCENTILE)));
    }

    private static BigDecimal toFraction(BigDecimal percent) {
        return percent.divide(PERCENT, MathContext.DECIMAL128);
    }

    /**
     * 경로별 최종 평가액과, 엔진과 같은 방식으로 고른 3구간.
     *
     * @param finalTotals 경로 순서대로의 최종 총 평가액(반올림 전)
     * @param lowFraction 나빴을 때 분위(예: 0.1)
     * @param highFraction 좋았을 때 분위(예: 0.9)
     */
    public record Result(List<BigDecimal> finalTotals, BigDecimal lowFraction, BigDecimal highFraction) {

        public Result {
            finalTotals = List.copyOf(finalTotals);
        }

        /** 경로 순서대로의 최종 금액(원 단위, 엔진과 같은 HALF_UP). */
        public List<Long> finalWon() {
            return finalTotals.stream().map(Result::toWon).toList();
        }

        /** 나빴을 때(하위 분위, 선형보간) 원 단위. */
        public long badWon() {
            return toWon(Percentiles.percentile(sorted(), lowFraction));
        }

        /** 평균(산술평균) 원 단위. */
        public long averageWon() {
            return toWon(Percentiles.mean(finalTotals.toArray(BigDecimal[]::new)));
        }

        /** 좋았을 때(상위 분위, 선형보간) 원 단위. */
        public long goodWon() {
            return toWon(Percentiles.percentile(sorted(), highFraction));
        }

        private BigDecimal[] sorted() {
            BigDecimal[] values = finalTotals.toArray(BigDecimal[]::new);
            Arrays.sort(values);
            return values;
        }

        private static long toWon(BigDecimal amount) {
            return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
    }
}
