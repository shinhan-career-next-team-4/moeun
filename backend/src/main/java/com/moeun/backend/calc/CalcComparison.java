package com.moeun.backend.calc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 현재 계획과 조건을 바꾼 계획의 비교(계약 internal-calc C2-3~C2-5). 대화 재계산(#32)이 쓰고, 대화 적용 판정(#44)과
 * 조건 비교(FR-CMP-02, #64)가 재사용한다. 숫자는 모두 Spring이 계산한다(원칙 1).
 *
 * @param current 현재 계획을 그대로 계산한 결과
 * @param changed 현재 계획에 바꾼 조건만 얹어 계산한 결과
 * @param conditions 두 계산에 실제 쓴 조건(기본금리·기본 비중을 채운 뒤)
 * @param diff changed − current
 */
public record CalcComparison(CalcResult current, CalcResult changed, Conditions conditions, Diff diff) {

    /** 시나리오 표시 순서. 응답·display·diff가 이 순서에 기댄다(계약 C2-4). */
    private static final List<Scenario> ORDER = List.of(Scenario.GOOD, Scenario.AVG, Scenario.BAD);

    /** 두 결과와 계산에 쓴 조건으로 비교를 만든다. diff는 양쪽에 모두 있는 시나리오만 담는다. */
    public static CalcComparison of(CalcResult current, CalcResult changed, CalcInput currentInput,
            CalcInput changedInput) {
        return of(current, changed, currentInput, changedInput, null, null);
    }

    /**
     * 종목을 바꾼 비교(D-097, #157). 종목 코드는 바꿨을 때만 두 쪽 모두 넣고, 바꾸지 않았으면 둘 다 null이다.
     */
    public static CalcComparison of(CalcResult current, CalcResult changed, CalcInput currentInput,
            CalcInput changedInput, String currentEtfProductCode, String changedEtfProductCode) {
        Conditions conditions = new Conditions(ConditionValues.from(currentInput, currentEtfProductCode),
                ConditionValues.from(changedInput, changedEtfProductCode));
        return new CalcComparison(current, changed, conditions, Diff.between(current, changed));
    }

    /**
     * 목표 대비 차이(changed.gapToGoal − current.gapToGoal, #285). 각 쪽은 자기 목표 기준이라 목표를 바꾸면
     * 예상 자산 차이와 다르다. raw 필드로 내보내지 않고 display에만 쓴다(계약 C2-5).
     *
     * @param scenario byScenario에 있는 시나리오(양쪽에 모두 있음)
     */
    public long gapToGoalDelta(Scenario scenario) {
        return Diff.find(changed, scenario).orElseThrow().gapToGoal()
                - Diff.find(current, scenario).orElseThrow().gapToGoal();
    }

    /**
     * 대화로 바꿀 수 있는 조건의 계산에 실제 쓴 값. 목표 금액은 결과의 {@code goalAmount}에 있다.
     *
     * @param monthlySavingAmount 월 저축액(원)
     * @param savingsRatioPct 적금 비중(%)
     * @param savingsRateInputPct 실제 쓴 적금 금리(기본금리를 썼어도 그 값)
     * @param targetPeriodMonths 목표 기간(개월)
     * @param etfProductCode 그쪽 계산의 ETF 종목 코드. 종목을 바꾼 비교에만 있고 아니면 null이라 JSON 키도 없다(C2-3)
     */
    public record ConditionValues(Long monthlySavingAmount, int savingsRatioPct, BigDecimal savingsRateInputPct,
            Integer targetPeriodMonths, @JsonInclude(JsonInclude.Include.NON_NULL) String etfProductCode) {

        static ConditionValues from(CalcInput input, String etfProductCode) {
            return new ConditionValues(input.monthlySavingAmount(), input.savingsRatioPct(), input.savingsRatePct(),
                    input.targetPeriodMonths(), etfProductCode);
        }
    }

    public record Conditions(ConditionValues current, ConditionValues changed) {
    }

    /**
     * @param baselineScenario 기준 차이를 볼 시나리오. 항상 byScenario 안에 있다(없으면 AVG)
     * @param byScenario 양쪽에 모두 있는 시나리오만, GOOD·AVG·BAD 순서
     */
    public record Diff(Scenario baselineScenario, List<ScenarioDiff> byScenario) {

        static Diff between(CalcResult current, CalcResult changed) {
            List<ScenarioDiff> byScenario = new ArrayList<>();
            for (Scenario scenario : ORDER) {
                Optional<ScenarioResult> before = find(current, scenario);
                Optional<ScenarioResult> after = find(changed, scenario);
                if (before.isPresent() && after.isPresent()) {
                    byScenario.add(ScenarioDiff.between(before.get(), after.get()));
                }
            }
            // findBaselineResult는 기준 시나리오가 없으면 다른 시나리오로 대신하므로, byScenario에 없으면 AVG를 가리킨다
            Scenario pointer = changed.findBaselineResult().map(ScenarioResult::scenario).orElse(Scenario.AVG);
            boolean isPresent = byScenario.stream().anyMatch(diff -> diff.scenario() == pointer);
            return new Diff(isPresent ? pointer : Scenario.AVG, List.copyOf(byScenario));
        }

        private static Optional<ScenarioResult> find(CalcResult result, Scenario scenario) {
            return result.scenarios().stream().filter(item -> item.scenario() == scenario).findFirst();
        }
    }

    /**
     * @param scenario 구간
     * @param afterTaxAssetDelta 세후 예상 자산 차이(원, 부호 있음)
     * @param principalDelta 원금 차이. "더 넣은 돈"
     * @param afterTaxGainDelta 세후 이익 차이. "더 늘어난 수익"
     * @param achievementChange 달성 여부의 전후
     */
    public record ScenarioDiff(Scenario scenario, long afterTaxAssetDelta, long principalDelta, long afterTaxGainDelta,
            AchievementChange achievementChange) {

        static ScenarioDiff between(ScenarioResult current, ScenarioResult changed) {
            return new ScenarioDiff(current.scenario(), changed.afterTaxAsset() - current.afterTaxAsset(),
                    changed.principal() - current.principal(), changed.afterTaxGain() - current.afterTaxGain(),
                    AchievementChange.of(current.isAchieved(), changed.isAchieved()));
        }
    }

    /** 달성 여부의 전후. */
    public enum AchievementChange {
        STAYS_ACHIEVED,
        STAYS_MISSED,
        MISS_TO_ACHIEVE,
        ACHIEVE_TO_MISS;

        static AchievementChange of(boolean before, boolean after) {
            if (before) {
                return after ? STAYS_ACHIEVED : ACHIEVE_TO_MISS;
            }
            return after ? MISS_TO_ACHIEVE : STAYS_MISSED;
        }
    }
}
