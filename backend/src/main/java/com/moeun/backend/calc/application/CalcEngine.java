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
import com.moeun.backend.refdata.Sp500Series;

/**
 * 계산 엔진(docs/calc-spec.md). 금융 계산은 이 엔진에서만 한다(NFR-INT-01).
 * <p>DB를 직접 읽지 않고 넣어 준 입력·파라미터·S&P500 데이터만 쓴다. 그래서 같은 입력, 같은 기준 데이터 버전,
 * 같은 {@link #ENGINE_VERSION}이면 언제 다시 계산해도 같은 결과가 나온다(NFR-INT-02).
 * <ul>
 *     <li>보유 자산 ≥ 목표 금액이면 계산하지 않고 {@link CalcStatus#ALREADY_EXCEEDED}(FR-RES-01)</li>
 *     <li>ETF 0%면 적금만 계산해 AVG 하나(#26)</li>
 *     <li>ETF가 들어가면 부트스트랩으로 GOOD·AVG·BAD(#27)</li>
 * </ul>
 * 기한 없음·월 저축액 모름은 해커톤 범위 밖이라 계산하지 않고 막는다(D-045, M-8).
 */
@Component
public class CalcEngine {

    /** 계산 규칙을 바꾸면 올린다. calc_run에 기록해 어떤 규칙으로 계산했는지 구분한다. */
    public static final String ENGINE_VERSION = "calc-1.1";

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);
    /** 응답·display·diff가 이 순서에 기댄다(계약 C2-4). */
    private static final List<Scenario> ETF_SCENARIO_ORDER = List.of(Scenario.GOOD, Scenario.AVG, Scenario.BAD);

    /**
     * 계획 하나를 계산한다.
     *
     * @param input 기본금리·기본 비중까지 채운 계산 조건
     * @param params 계산 파라미터(세율, 3구간 산정 기준)
     * @param sp500 활성 S&P500 월말 종가. ETF가 들어가면 꼭 있어야 하고, ETF 0%·이미 목표 초과면 쓰지 않는다
     * @throws IllegalArgumentException 기한 없음·월 저축액 모름(해커톤 범위 밖), 또는 ETF 계획에 S&P500이 없을 때
     * @throws IllegalStateException ETF 계산에 필요한 S&P500 종가가 모자랄 때
     */
    public CalcResult calculate(CalcInput input, CalcParams params, Sp500Series sp500) {
        rejectOutOfScope(input);
        int targetPeriodMonths = input.targetPeriodMonths();
        YearMonth targetMonth = input.calcStartMonth().plusMonths(targetPeriodMonths);
        BigDecimal taxRatePct = params.getValue(CalcParamKey.TAX_RATE_PCT);

        if (input.hasExceededGoal()) {
            // 이미 목표 이상이면 시나리오를 계산하지 않는다. 화면은 초과 안내만 보여 준다(같으면 초과, CF-03)
            return new CalcResult(CalcStatus.ALREADY_EXCEEDED, input.goalAmount(), input.calcStartMonth(),
                    targetMonth, input.baselineScenario(), List.of(), null,
                    createAssumptions(input, taxRatePct, null), createDataVersions(params, null));
        }

        BigDecimal monthlySaving = BigDecimal.valueOf(input.monthlySavingAmount());
        // 월 저축액 중 적금 몫 Ms = M × 적금비중 ÷ 100, ETF 몫 Me = M − Ms. 나눈 몫도 반올림하지 않는다(calc-spec 3절)
        BigDecimal savingsDeposit = monthlySaving.multiply(BigDecimal.valueOf(input.savingsRatioPct()))
                .divide(PERCENT, MathContext.DECIMAL128);
        SavingsCalculator savings = new SavingsCalculator(savingsDeposit, input.savingsRatePct(), taxRatePct);

        List<ScenarioResult> scenarios = input.hasEtf()
                ? calculateEtfScenarios(input, params, sp500, savings, monthlySaving.subtract(savingsDeposit))
                : List.of(calculateSavingsOnly(input, savings));
        Sp500Series usedSp500 = input.hasEtf() ? sp500 : null;
        return new CalcResult(CalcStatus.CALCULATED, input.goalAmount(), input.calcStartMonth(), targetMonth,
                input.baselineScenario(), scenarios, null,
                createAssumptions(input, taxRatePct, usedSp500), createDataVersions(params, usedSp500));
    }

    /** ETF 0%: 구간이 갈리지 않으므로 AVG 하나(FR-RES-03). */
    private static ScenarioResult calculateSavingsOnly(CalcInput input, SavingsCalculator savings) {
        BigDecimal heldAssets = BigDecimal.valueOf(input.heldAssets());
        List<BigDecimal> totals = new ArrayList<>(input.targetPeriodMonths() + 1);
        for (int k = 0; k <= input.targetPeriodMonths(); k++) {
            totals.add(heldAssets.add(savings.valueAt(k)));
        }
        return ScenarioResults.create(Scenario.AVG, input.goalAmount(), createMonthlyPoints(input, totals));
    }

    private static List<ScenarioResult> calculateEtfScenarios(CalcInput input, CalcParams params, Sp500Series sp500,
            SavingsCalculator savings, BigDecimal etfDeposit) {
        if (sp500 == null) {
            throw new IllegalArgumentException("S&P500 data is required for plans with ETF");
        }
        int observationMonths = params.getValue(CalcParamKey.SCENARIO_OBSERVATION_MONTHS).intValueExact();
        List<BigDecimal> observedReturns = MonthlyReturns.fromCloses(sp500.monthlyCloses(), observationMonths);
        BootstrapScenarios bootstrap = new BootstrapScenarios(observedReturns,
                params.getValue(CalcParamKey.BOOTSTRAP_PATH_COUNT).intValueExact(),
                params.getValue(CalcParamKey.BOOTSTRAP_SEED).longValueExact(),
                toFraction(params.getValue(CalcParamKey.SCENARIO_LOW_PERCENTILE)),
                toFraction(params.getValue(CalcParamKey.SCENARIO_HIGH_PERCENTILE)));
        EtfPath etf = new EtfPath(etfDeposit, params.getValue(CalcParamKey.TAX_RATE_PCT));
        BootstrapScenarios.Series series = bootstrap.simulate(input.targetPeriodMonths(),
                BigDecimal.valueOf(input.heldAssets()), savings, etf);

        List<ScenarioResult> results = new ArrayList<>(ETF_SCENARIO_ORDER.size());
        for (Scenario scenario : ETF_SCENARIO_ORDER) {
            List<BigDecimal> totals = switch (scenario) {
                case GOOD -> series.good();
                case AVG -> series.average();
                case BAD -> series.bad();
            };
            results.add(ScenarioResults.create(scenario, input.goalAmount(), createMonthlyPoints(input, totals)));
        }
        return results;
    }

    /**
     * k = 0 … N의 월별 점(calc-spec 4절). 첫 점은 납입 전 출발점(보유 자산, 운용 수익 없음 CP-03)이고,
     * k번째 점은 k번째 납입과 그 달 운용 뒤의 값이다. 금액은 여기서 한 번만 원 단위로 반올림한다.
     */
    private static List<MonthlyPoint> createMonthlyPoints(CalcInput input, List<BigDecimal> totals) {
        List<MonthlyPoint> points = new ArrayList<>(totals.size());
        for (int k = 0; k < totals.size(); k++) {
            long principal = input.heldAssets() + input.monthlySavingAmount() * k;
            points.add(new MonthlyPoint(input.calcStartMonth().plusMonths(k), principal, toWon(totals.get(k))));
        }
        return points;
    }

    private static Assumptions createAssumptions(CalcInput input, BigDecimal taxRatePct, Sp500Series usedSp500) {
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
        // 기본금리를 썼는지는 조건 해석(ConditionResolver, #25)이 알려 준다. 그 전까지는 넣어 준 금리를 그대로 쓴 것으로 둔다
        return new Assumptions(input.savingsRatePct(), false, null, input.savingsRatioPct(), taxRatePct, method,
                usedSp500 == null ? null : usedSp500.version().baseDate(), excludedItems);
    }

    private static AppliedDataVersions createDataVersions(CalcParams params, Sp500Series usedSp500) {
        Long sp500VersionId = usedSp500 == null ? null : usedSp500.version().id();
        return new AppliedDataVersions(null, sp500VersionId, params.version().id(), ENGINE_VERSION);
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

    private static BigDecimal toFraction(BigDecimal percent) {
        return percent.divide(PERCENT, MathContext.DECIMAL128);
    }

    private static long toWon(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
