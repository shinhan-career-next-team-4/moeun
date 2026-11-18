package com.moeun.backend.calc.application;

import java.math.BigDecimal;
import java.time.YearMonth;

import org.springframework.stereotype.Component;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.PlanView;
import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.SavingsAvgRateData;

/**
 * 저장된 계획과 바꾼 조건을 계산 엔진 입력({@link CalcInput})으로 해석한다(계약 C2-8).
 * <ul>
 *     <li>계산 시작월은 항상 계획에 저장된 값이다. 대화·비교로 다시 계산해도 바뀌지 않는다(D-013, D-032)</li>
 *     <li>금리를 비운 계획이면 기본금리(은행연합회 12개월 정기적금 평균, D-012, D-034)를 쓴다</li>
 *     <li>비중을 비운 계획이면 기본 비중 파라미터(적금 5 : ETF 5, FR-INP-08 Won't)를 쓴다</li>
 *     <li>바꾼 조건은 그 요청 한 건만 현재 계획에 얹는다. 이전 요청의 변경은 쌓지 않는다(D-041)</li>
 * </ul>
 * DB를 직접 읽지 않고 넣어 준 계획·기준 데이터만 쓴다. 꺼내서 넘기는 일은 호출하는 서비스가 한다.
 */
@Component
public class ConditionResolver {

    /**
     * 현재 계획을 해석한다.
     *
     * @param plan 회원의 현재 계획
     * @param defaultRate 활성 적금 평균금리. 계획이 금리를 비웠을 때만 쓴다
     * @param params 활성 계산 파라미터(기본 비중)
     * @throws IllegalStateException 계획의 필수 값이 비었거나, 금리를 비웠는데 기본금리 데이터가 없을 때
     */
    public ResolvedConditions resolve(PlanView plan, SavingsAvgRateData defaultRate, CalcParams params) {
        return resolveChanged(plan, new ConditionOverride(null, null, null, null), defaultRate, params);
    }

    /**
     * 현재 계획에 이번 요청의 바꾼 조건을 얹어 해석한다. 바꾸지 않은 항목은 현재 계획의 해석 값이다.
     *
     * @param plan 회원의 현재 계획(기준, C2-8)
     * @param changes 이번 요청에서 바꾼 조건. null 필드는 바꾸지 않음
     * @param defaultRate 활성 적금 평균금리. 금리를 비운 계획이면서 금리를 바꾸지 않았을 때만 쓴다
     * @param params 활성 계산 파라미터(기본 비중)
     * @throws IllegalStateException 계획의 필수 값이 비었거나, 기본금리가 필요한데 데이터가 없을 때
     */
    public ResolvedConditions resolveChanged(PlanView plan, ConditionOverride changes, SavingsAvgRateData defaultRate,
            CalcParams params) {
        // 기간·월 저축액은 rules.yml에서 필수라 저장된 계획에 늘 있다. CalcInput은 null을 '기한 미정'·'모름'으로 받지만
        // 해커톤 범위 밖(D-045)이라 여기서는 빈 값을 깨진 데이터로 본다. 이 입력을 열면 이 검사도 함께 바꾼다
        long goalAmount = require(plan.goalAmount(), "goalAmount");
        int targetPeriodMonths = changes.targetPeriodMonths() != null
                ? changes.targetPeriodMonths() : require(plan.targetPeriodMonths(), "targetPeriodMonths");
        long heldAssets = require(plan.heldAssets(), "heldAssets");
        long monthlySavingAmount = changes.monthlySavingAmount() != null
                ? changes.monthlySavingAmount() : require(plan.monthlySavingAmount(), "monthlySavingAmount");
        // 비중을 바꾸지 않은 요청은 현재 계획의 해석된 비중을 그대로 쓴다(C2-8 방어 규칙)
        int savingsRatioPct = changes.savingsRatioPct() != null
                ? changes.savingsRatioPct() : resolveRatio(plan, params);

        BigDecimal enteredRate = changes.savingsRateInputPct() != null
                ? changes.savingsRateInputPct() : plan.savingsRateInputPct();
        if (enteredRate != null) {
            CalcInput input = createInput(plan, goalAmount, targetPeriodMonths, heldAssets, monthlySavingAmount,
                    enteredRate, savingsRatioPct);
            return new ResolvedConditions(input, false, null, null);
        }
        if (defaultRate == null) {
            throw new IllegalStateException("default savings rate is required but not loaded");
        }
        CalcInput input = createInput(plan, goalAmount, targetPeriodMonths, heldAssets, monthlySavingAmount,
                defaultRate.avgRatePct(), savingsRatioPct);
        return new ResolvedConditions(input, true, defaultRate.baseMonth(), defaultRate.version().id());
    }

    private static int resolveRatio(PlanView plan, CalcParams params) {
        if (plan.savingsRatioPct() != null) {
            return plan.savingsRatioPct();
        }
        return params.getValue(CalcParamKey.DEFAULT_SAVINGS_RATIO_PCT).intValueExact();
    }

    private static CalcInput createInput(PlanView plan, long goalAmount, int targetPeriodMonths, long heldAssets,
            long monthlySavingAmount, BigDecimal savingsRatePct, int savingsRatioPct) {
        YearMonth calcStartMonth = YearMonth.parse(require(plan.calcStartMonth(), "calcStartMonth"));
        Scenario baselineScenario = Scenario.valueOf(require(plan.baselineScenario(), "baselineScenario"));
        return new CalcInput(goalAmount, targetPeriodMonths, calcStartMonth, heldAssets, monthlySavingAmount,
                savingsRatePct, savingsRatioPct, baselineScenario);
    }

    /** 저장할 때 검증한 필수 값이라 비어 있으면 데이터가 깨진 것이다. 금액 대신 항목 이름만 남긴다. */
    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalStateException("plan " + field + " is missing");
        }
        return value;
    }

    /**
     * 해석한 계산 조건과 기본금리 정보. 기본금리 정보는 가정 보기(FR-RES-06)와 calc_run의 금리 버전 기록에 쓴다.
     *
     * @param input 계산 엔진 입력
     * @param isDefaultSavingsRate 기본금리를 썼는지
     * @param savingsRateBaseMonth 기본금리의 기준월. null = 사용자가 넣은 금리를 씀
     * @param savingsRateVersionId 기본금리 데이터 버전. null = 사용자가 넣은 금리를 씀
     */
    public record ResolvedConditions(CalcInput input, boolean isDefaultSavingsRate, YearMonth savingsRateBaseMonth,
            Long savingsRateVersionId) {
    }
}
