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
import com.moeun.backend.calc.ScenarioResult;
import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.CalcParams;

/**
 * 계산 엔진(docs/calc-spec.md). 금융 계산은 이 엔진에서만 한다(NFR-INT-01).
 * <p>DB를 직접 읽지 않고 넣어 준 입력과 파라미터만 쓴다. 그래서 같은 입력, 같은 파라미터 버전,
 * 같은 {@link #ENGINE_VERSION}이면 언제 다시 계산해도 같은 결과가 나온다(NFR-INT-02).
 * <p>지금은 적금만(ETF 0%) 계산한다(#26). ETF 3구간, 이미 목표를 넘은 경우, 기한 없음·월 저축액 모름은 #27이 맡는다.
 */
@Component
public class CalcEngine {

    /** 계산 규칙을 바꾸면 올린다. calc_run에 기록해 어떤 규칙으로 계산했는지 구분한다. */
    public static final String ENGINE_VERSION = "calc-1.0";

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);
    private static final List<ExcludedItem> SAVINGS_ONLY_EXCLUDED_ITEMS =
            List.of(ExcludedItem.HELD_ASSET_RETURN, ExcludedItem.SAVINGS_REDEPOSIT);

    /**
     * 계획 하나를 계산한다.
     *
     * @param input 기본금리·기본 비중까지 채운 계산 조건
     * @param params 계산 파라미터(세율 등)
     * @throws IllegalArgumentException 아직 계산하지 않는 입력(ETF 포함, 이미 목표 초과, 기한 없음, 월 저축액 모름)
     */
    public CalcResult calculate(CalcInput input, CalcParams params) {
        rejectUnsupported(input);
        int targetPeriodMonths = input.targetPeriodMonths();
        BigDecimal monthlySaving = BigDecimal.valueOf(input.monthlySavingAmount());
        BigDecimal taxRatePct = params.getValue(CalcParamKey.TAX_RATE_PCT);
        // 월 저축액 중 적금 몫 Ms = M × 적금비중 ÷ 100. 나눈 몫도 반올림하지 않는다(calc-spec 3절)
        BigDecimal savingsDeposit = monthlySaving.multiply(BigDecimal.valueOf(input.savingsRatioPct()))
                .divide(PERCENT, MathContext.DECIMAL128);
        SavingsCalculator savings = new SavingsCalculator(savingsDeposit, input.savingsRatePct(), taxRatePct);

        List<MonthlyPoint> points = createMonthlyPoints(input, targetPeriodMonths, savings);
        ScenarioResult average = createScenarioResult(Scenario.AVG, input.goalAmount(), points);
        YearMonth targetMonth = input.calcStartMonth().plusMonths(targetPeriodMonths);
        return new CalcResult(CalcStatus.CALCULATED, input.goalAmount(), input.calcStartMonth(), targetMonth,
                input.baselineScenario(), List.of(average), null,
                createAssumptions(input, taxRatePct), createDataVersions(params));
    }

    /**
     * k = 0 … N의 월별 점(calc-spec 4절). 첫 점은 납입 전 출발점(보유 자산, 운용 수익 없음 CP-03)이고,
     * k번째 점은 k번째 납입과 그 달 운용 뒤의 값이다. 같은 달이 두 번 나오지 않는다.
     */
    private static List<MonthlyPoint> createMonthlyPoints(CalcInput input, int targetPeriodMonths,
            SavingsCalculator savings) {
        BigDecimal heldAssets = BigDecimal.valueOf(input.heldAssets());
        List<MonthlyPoint> points = new ArrayList<>(targetPeriodMonths + 1);
        for (int k = 0; k <= targetPeriodMonths; k++) {
            long principal = input.heldAssets() + input.monthlySavingAmount() * k;
            long afterTaxAsset = toWon(heldAssets.add(savings.valueAt(k)));
            points.add(new MonthlyPoint(input.calcStartMonth().plusMonths(k), principal, afterTaxAsset));
        }
        return points;
    }

    private static ScenarioResult createScenarioResult(Scenario scenario, long goalAmount, List<MonthlyPoint> points) {
        MonthlyPoint last = points.get(points.size() - 1);
        // 판정과 도달 시점은 반올림한 값으로 본다. 그래서 "달성인데 도달 시점 없음"이 생기지 않는다(calc-spec 8절)
        long gapToGoal = last.afterTaxAsset() - goalAmount;
        YearMonth goalReachMonth = points.stream()
                .filter(point -> point.afterTaxAsset() >= goalAmount)
                .map(MonthlyPoint::month)
                .findFirst()
                .orElse(null);
        return new ScenarioResult(scenario, null, last.afterTaxAsset(), last.principal(),
                last.afterTaxAsset() - last.principal(), gapToGoal, gapToGoal >= 0, goalReachMonth, points);
    }

    private static Assumptions createAssumptions(CalcInput input, BigDecimal taxRatePct) {
        // 기본금리를 썼는지는 조건 해석(ConditionResolver, #25)이 알려 준다. 그 전까지는 넣어 준 금리를 그대로 쓴 것으로 둔다
        return new Assumptions(input.savingsRatePct(), false, null, input.savingsRatioPct(), taxRatePct,
                null, null, SAVINGS_ONLY_EXCLUDED_ITEMS);
    }

    private static AppliedDataVersions createDataVersions(CalcParams params) {
        return new AppliedDataVersions(null, null, params.version().id(), ENGINE_VERSION);
    }

    private static void rejectUnsupported(CalcInput input) {
        // 금융 값은 로그·메시지에 남기지 않기로 해서 이유만 적는다
        if (input.targetPeriodMonths() == null) {
            throw new IllegalArgumentException("target period is required");
        }
        if (input.monthlySavingAmount() == null) {
            throw new IllegalArgumentException("monthly saving amount is required");
        }
        if (input.hasEtf()) {
            throw new IllegalArgumentException("plans with ETF are not supported yet");
        }
        if (input.hasExceededGoal()) {
            throw new IllegalArgumentException("plans that already exceed the goal are not supported yet");
        }
    }

    private static long toWon(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
