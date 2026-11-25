package com.moeun.backend.calc.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.moeun.backend.calc.AppliedDataVersions;
import com.moeun.backend.calc.Assumptions;
import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.CalcStatus;
import com.moeun.backend.calc.ExcludedItem;
import com.moeun.backend.calc.MonthlyPoint;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.ScenarioMethod;
import com.moeun.backend.calc.ScenarioResult;
import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.EtfSeries;

/**
 * 계산 엔진(docs/calc-spec.md). 금융 계산은 이 엔진에서만 한다(NFR-INT-01).
 * <p>DB를 직접 읽지 않고 넣어 준 입력·파라미터·S&P500 데이터만 쓴다. 그래서 같은 입력, 같은 기준 데이터 버전,
 * 같은 {@link #ENGINE_VERSION}이면 언제 다시 계산해도 같은 결과가 나온다(NFR-INT-02).
 * <ul>
 *     <li>보유 자산 ≥ 목표 금액이어도 계산하되 상태는 {@link CalcStatus#ALREADY_EXCEEDED}(FR-RES-01, #370)</li>
 *     <li>ETF 0%면 적금만 계산해 AVG 하나(#26)</li>
 *     <li>ETF가 들어가면 부트스트랩으로 GOOD·AVG·BAD(#27)</li>
 * </ul>
 * 기한 없음·월 저축액 모름은 해커톤 범위 밖이라 계산하지 않고 막는다(D-045, M-8).
 */
@Component
public class CalcEngine {

    /** 계산 규칙을 바꾸면 올린다. calc_run에 기록해 어떤 규칙으로 계산했는지 구분한다. */
    public static final String ENGINE_VERSION = "calc-1.2";   // 1.2: ETF 차익 세율을 종목별로(#150)

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);
    /** 응답·display·diff가 이 순서에 기댄다(계약 C2-4). */
    private static final List<Scenario> ETF_SCENARIO_ORDER = List.of(Scenario.GOOD, Scenario.AVG, Scenario.BAD);

    /**
     * 조건 해석({@link ConditionResolver}) 결과로 계산한다. 기본금리를 썼는지·기준월은 가정 보기에,
     * 기본금리 데이터 버전은 데이터 버전(calc_run 기록)에 남긴다. 결과 화면·planContext는 이 진입점을 쓴다.
     */
    public CalcResult calculate(ConditionResolver.ResolvedConditions resolved, CalcParams params, EtfSeries sp500) {
        return calculate(resolved.input(), params, sp500, DefaultRate.from(resolved), null);
    }

    /**
     * 부트스트랩 경로 수를 줄여 빠르게 계산한다. 목표 달성 여부를 많이 시험하는 탐색(대안 추천)의 판정에만 쓰고,
     * 화면에 보이는 값과 저장하는 값은 {@link #calculate}로 정확히 계산한다. 경로 수가 설정값보다 크면 설정값을 쓴다.
     */
    public CalcResult calculateForSearch(ConditionResolver.ResolvedConditions resolved, CalcParams params,
            EtfSeries sp500, int searchPathCount) {
        return calculate(resolved.input(), params, sp500, DefaultRate.from(resolved), searchPathCount);
    }

    /**
     * 계산하지 않고 이번 계산이 쓸 기준 데이터 버전과 엔진 버전만 구한다. 부트스트랩을 돌리지 않으므로 빠르다.
     * calc_run을 기록만 하면 되는 곳(PlanChanged 리스너)이 쓴다. {@link #calculate}의 데이터 버전과 항상 같다.
     */
    public AppliedDataVersions determineDataVersions(ConditionResolver.ResolvedConditions resolved,
            CalcParams params, EtfSeries sp500) {
        CalcInput input = resolved.input();
        rejectOutOfScope(input);
        EtfSeries usedSp500 = null;
        if (input.hasEtf()) {
            // calculate가 거절하는 경우(ETF 가격 데이터 없음, 종가 부족)는 여기서도 같은 예외로 막는다.
            // 그러지 않으면 ETF 가격 버전이 빈 기록이 남아 "ETF 0%"처럼 읽히고 다시 계산할 수도 없다(NFR-INT-02)
            requireSp500(sp500);
            MonthlyReturns.fromCloses(sp500.monthlyCloses(), observationMonths(params));
            usedSp500 = sp500;
        }
        return createDataVersions(params, usedSp500, DefaultRate.from(resolved));
    }

    /**
     * 계획 하나를 계산한다. 기본금리 정보 없이 넣어 준 금리를 그대로 쓴 것으로 기록한다.
     *
     * @param input 기본금리·기본 비중까지 채운 계산 조건
     * @param params 계산 파라미터(세율, 3구간 산정 기준)
     * @param sp500 계산할 ETF 종목의 월말 종가와 종목 정보(매매차익 세율, #150). ETF가 들어가면 꼭 있어야 하고,
     *     ETF 0%·이미 목표 초과면 쓰지 않는다
     * @throws IllegalArgumentException 기한 없음·월 저축액 모름(해커톤 범위 밖), 또는 ETF 계획에 가격 데이터가 없을 때
     * @throws IllegalStateException ETF 계산에 필요한 종가가 모자랄 때
     */
    public CalcResult calculate(CalcInput input, CalcParams params, EtfSeries sp500) {
        return calculate(input, params, sp500, DefaultRate.NONE, null);
    }

    private CalcResult calculate(CalcInput input, CalcParams params, EtfSeries sp500, DefaultRate defaultRate,
            Integer pathCountLimit) {
        rejectOutOfScope(input);
        int targetPeriodMonths = input.targetPeriodMonths();
        YearMonth targetMonth = input.calcStartMonth().plusMonths(targetPeriodMonths);
        BigDecimal taxRatePct = params.getValue(CalcParamKey.TAX_RATE_PCT);

        BigDecimal monthlySaving = BigDecimal.valueOf(input.monthlySavingAmount());
        // 월 저축액 중 적금 몫 Ms = M × 적금비중 ÷ 100, ETF 몫 Me = M − Ms. 나눈 몫도 반올림하지 않는다(calc-spec 3절)
        BigDecimal savingsDeposit = monthlySaving.multiply(BigDecimal.valueOf(input.savingsRatioPct()))
                .divide(PERCENT, MathContext.DECIMAL128);
        SavingsCalculator savings = new SavingsCalculator(savingsDeposit, input.savingsRatePct(), taxRatePct);

        List<ScenarioResult> scenarios = input.hasEtf()
                ? calculateEtfScenarios(input, params, sp500, savings, monthlySaving.subtract(savingsDeposit),
                        pathCountLimit)
                : List.of(calculateSavingsOnly(input, savings));
        EtfSeries usedSp500 = input.hasEtf() ? sp500 : null;
        // 이미 목표 이상이어도 계산한다(#370). 상태만 ALREADY_EXCEEDED로 남겨 화면이 안내를 함께 보이게 한다(같으면 초과, CF-03)
        CalcStatus status = input.hasExceededGoal() ? CalcStatus.ALREADY_EXCEEDED : CalcStatus.CALCULATED;
        return new CalcResult(status, input.goalAmount(), input.calcStartMonth(), targetMonth,
                input.baselineScenario(), scenarios, null,
                createAssumptions(input, taxRatePct, usedSp500, defaultRate),
                createDataVersions(params, usedSp500, defaultRate));
    }

    /** ETF 0%: 구간이 갈리지 않으므로 AVG 하나(FR-RES-03). */
    private static ScenarioResult calculateSavingsOnly(CalcInput input, SavingsCalculator savings) {
        BigDecimal heldAssets = BigDecimal.valueOf(input.heldAssets());
        List<BigDecimal> totals = new ArrayList<>(input.targetPeriodMonths() + 1);
        for (int k = 0; k <= input.targetPeriodMonths(); k++) {
            totals.add(heldAssets.add(savings.valueAt(k)));
        }
        return ScenarioResults.create(Scenario.AVG, input.goalAmount(), createMonthlyPoints(input, totals, savings));
    }

    private static List<ScenarioResult> calculateEtfScenarios(CalcInput input, CalcParams params, EtfSeries sp500,
            SavingsCalculator savings, BigDecimal etfDeposit, Integer pathCountLimit) {
        requireSp500(sp500);
        int configuredPathCount = params.getValue(CalcParamKey.BOOTSTRAP_PATH_COUNT).intValueExact();
        int pathCount = pathCountLimit == null ? configuredPathCount : Math.min(configuredPathCount, pathCountLimit);
        List<BigDecimal> observedReturns = MonthlyReturns.fromCloses(sp500.monthlyCloses(), observationMonths(params));
        BootstrapScenarios bootstrap = new BootstrapScenarios(observedReturns,
                pathCount,
                params.getValue(CalcParamKey.BOOTSTRAP_SEED).longValueExact(),
                toFraction(params.getValue(CalcParamKey.SCENARIO_LOW_PERCENTILE)),
                toFraction(params.getValue(CalcParamKey.SCENARIO_HIGH_PERCENTILE)));
        // ETF 차익 세율은 종목 정보(그 가격 데이터 버전에 묶인 값)에서 온다. 국내 주식형은 0이다(#150)
        EtfPath etf = new EtfPath(etfDeposit, sp500.product().gainTaxRatePct());
        BootstrapScenarios.Series series = bootstrap.simulate(input.targetPeriodMonths(),
                BigDecimal.valueOf(input.heldAssets()), savings, etf);

        List<ScenarioResult> results = new ArrayList<>(ETF_SCENARIO_ORDER.size());
        for (Scenario scenario : ETF_SCENARIO_ORDER) {
            List<BigDecimal> totals = switch (scenario) {
                case GOOD -> series.good();
                case AVG -> series.average();
                case BAD -> series.bad();
            };
            results.add(ScenarioResults.create(scenario, input.goalAmount(),
                    createMonthlyPoints(input, totals, savings)));
        }
        return results;
    }

    /**
     * k = 0 … N의 월별 점(calc-spec 4절). 첫 점은 납입 전 출발점(보유 자산, 운용 수익 없음 CP-03)이고,
     * k번째 점은 k번째 납입과 그 달 운용 뒤의 값이다. 금액은 여기서 한 번만 원 단위로 반올림한다.
     */
    private static List<MonthlyPoint> createMonthlyPoints(CalcInput input, List<BigDecimal> totals,
            SavingsCalculator savings) {
        List<MonthlyPoint> points = new ArrayList<>(totals.size());
        for (int k = 0; k < totals.size(); k++) {
            long principal = input.heldAssets() + input.monthlySavingAmount() * k;
            // 적금 이자 = 적금 세후 평가액 − 적금에 넣은 원금. 이 점에서 한 번만 원 단위로 반올림한다
            long savingsPaidIn = toWon(savings.paidIn(k));
            long savingsInterest = toWon(savings.valueAt(k)) - savingsPaidIn;
            points.add(new MonthlyPoint(input.calcStartMonth().plusMonths(k), principal, toWon(totals.get(k)),
                    savingsInterest));
        }
        return points;
    }

    private static Assumptions createAssumptions(CalcInput input, BigDecimal taxRatePct, EtfSeries usedSp500,
            DefaultRate defaultRate) {
        List<ExcludedItem> excludedItems = new ArrayList<>();
        if (usedSp500 != null) {
            excludedItems.add(ExcludedItem.ETF_TOTAL_EXPENSE);
            excludedItems.add(ExcludedItem.EXCHANGE_RATE);
            excludedItems.add(ExcludedItem.ETF_DISTRIBUTION);
        }
        excludedItems.add(ExcludedItem.HELD_ASSET_RETURN);
        if (input.savingsRatioPct() > 0) {
            excludedItems.add(ExcludedItem.SAVINGS_REDEPOSIT);
        }
        ScenarioMethod method = usedSp500 == null ? null : ScenarioMethod.ROLLING_DISTRIBUTION;
        EtfProduct product = usedSp500 == null ? null : usedSp500.product();
        return new Assumptions(input.savingsRatePct(), defaultRate.isDefault(), defaultRate.baseMonth(),
                input.savingsRatioPct(), taxRatePct, method,
                usedSp500 == null ? null : usedSp500.version().baseDate(), excludedItems,
                product == null ? null : product.code(), product == null ? null : product.displayName(),
                product == null ? null : product.gainTaxRatePct());
    }

    private static AppliedDataVersions createDataVersions(CalcParams params, EtfSeries usedSp500,
            DefaultRate defaultRate) {
        Long sp500VersionId = usedSp500 == null ? null : usedSp500.version().id();
        return new AppliedDataVersions(defaultRate.versionId(), sp500VersionId, params.version().id(),
                ENGINE_VERSION);
    }

    /**
     * 기본금리(은행연합회 평균)를 썼는지와 그 기준월·데이터 버전. 사용자가 금리를 넣었으면 모두 비어 있다.
     *
     * @param isDefault 기본금리를 썼는지
     * @param baseMonth 기본금리 기준월
     * @param versionId 기본금리 데이터 버전
     */
    private record DefaultRate(boolean isDefault, YearMonth baseMonth, Long versionId) {

        static final DefaultRate NONE = new DefaultRate(false, null, null);

        static DefaultRate from(ConditionResolver.ResolvedConditions resolved) {
            return new DefaultRate(resolved.isDefaultSavingsRate(), resolved.savingsRateBaseMonth(),
                    resolved.savingsRateVersionId());
        }
    }

    private static void rejectOutOfScope(CalcInput input) {
        // 금융 값은 로그·메시지에 남기지 않기로 해서 이유만 적는다
        if (input.targetPeriodMonths() == null) {
            throw new IllegalArgumentException("target period is required");
        }
        if (input.monthlySavingAmount() == null) {
            throw new IllegalArgumentException("monthly saving amount is required");
        }
    }

    private static void requireSp500(EtfSeries sp500) {
        if (sp500 == null) {
            throw new IllegalArgumentException("ETF price data is required for plans with ETF");
        }
    }

    private static int observationMonths(CalcParams params) {
        return params.getValue(CalcParamKey.SCENARIO_OBSERVATION_MONTHS).intValueExact();
    }

    private static BigDecimal toFraction(BigDecimal percent) {
        return percent.divide(PERCENT, MathContext.DECIMAL128);
    }

    private static long toWon(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
