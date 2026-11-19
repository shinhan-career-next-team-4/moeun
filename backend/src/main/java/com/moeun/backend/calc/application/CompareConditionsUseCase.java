package com.moeun.backend.calc.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.calc.AppliedDataVersions;
import com.moeun.backend.calc.CalcComparison;
import com.moeun.backend.calc.CalcRejectedException;
import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.plan.PlanView;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.ReferenceDataService;
import com.moeun.backend.refdata.SavingsAvgRateData;
import com.moeun.backend.refdata.Sp500Series;

/**
 * 대화 재계산 유스케이스(계약 internal-calc C2-2 검사 순서 7~9, C2-7, C2-8).
 * <p>기준은 항상 그 회원의 현재 계획이고, 이번 요청의 바꾼 조건만 얹는다(이전 변경은 쌓지 않음, D-041). 시작월은 현재
 * 계획의 값이다. current와 changed는 같은 기준 데이터·엔진 버전으로 계산하고 그 버전으로 calc_run(CHAT) 한 행을 남긴다.
 * <p>display까지 이 트랜잭션 안에서 만든다. 기록 뒤에 실패해도 기록이 함께 롤백되어 실패한 요청에는 행이 없다.
 */
@Service
public class CompareConditionsUseCase {

    private static final String CURRENT = "current.";
    private static final String CHANGED = "changed.";
    private static final String CONDITIONS_CURRENT = "conditions.current.";
    private static final String CONDITIONS_CHANGED = "conditions.changed.";

    private final PlanSaveService planSaveService;
    private final ReferenceDataService referenceDataService;
    private final ConditionResolver conditionResolver;
    private final CalcEngine calcEngine;
    private final CalcRunRecorder calcRunRecorder;
    private final ResultDisplayBuilder displayBuilder;
    private final Formatter formatter;

    public CompareConditionsUseCase(PlanSaveService planSaveService, ReferenceDataService referenceDataService,
            ConditionResolver conditionResolver, CalcEngine calcEngine, CalcRunRecorder calcRunRecorder,
            ResultDisplayBuilder displayBuilder, Formatter formatter) {
        this.planSaveService = planSaveService;
        this.referenceDataService = referenceDataService;
        this.conditionResolver = conditionResolver;
        this.calcEngine = calcEngine;
        this.calcRunRecorder = calcRunRecorder;
        this.displayBuilder = displayBuilder;
        this.formatter = formatter;
    }

    /**
     * @param memberId 계산할 회원
     * @param changes 검증을 통과한 바꾼 조건(하나 이상)
     * @throws CalcRejectedException 계획 없음(409 NO_PLAN), 이미 목표 초과(409 ALREADY_EXCEEDED)
     */
    @Transactional
    public ComparisonResult compare(long memberId, ConditionOverride changes) {
        PlanView plan = planSaveService.findCurrent(memberId).orElseThrow(CalcRejectedException::noPlan);
        CalcParams params = referenceDataService.findActiveCalcParams()
                .orElseThrow(() -> new IllegalStateException("active calc params are not loaded"));
        Sp500Series sp500 = referenceDataService.findActiveSp500Series().orElse(null);
        SavingsAvgRateData defaultRate = referenceDataService.findActiveSavingsAvgRate().orElse(null);

        ConditionResolver.ResolvedConditions current = conditionResolver.resolve(plan, defaultRate, params);
        if (current.input().hasExceededGoal()) {
            // 대화로 목표·보유 자산을 바꿀 수 없어 changed도 초과다. current도 계산하지 않는다(C3-2)
            throw CalcRejectedException.alreadyExceeded();
        }
        ConditionResolver.ResolvedConditions changed = conditionResolver.resolveChanged(plan, changes, defaultRate,
                params);
        CalcResult currentResult = calcEngine.calculate(current, params, sp500);
        CalcResult changedResult = calcEngine.calculate(changed, params, sp500);
        AppliedDataVersions versions = merge(currentResult.dataVersions(), changedResult.dataVersions());
        currentResult = withVersions(currentResult, versions);
        changedResult = withVersions(changedResult, versions);

        long calcRunId = calcRunRecorder.recordChatRun(plan.currentVersionId(), versions);
        CalcComparison comparison = CalcComparison.of(currentResult, changedResult, current.input(),
                changed.input());
        return new ComparisonResult(calcRunId, comparison, buildDisplay(comparison, current, changed));
    }

    /**
     * 두 계산이 함께 쓴 기준 데이터 버전. 한쪽만 쓴 데이터(예: 비중을 100으로 바꿔 changed가 S&P500을 안 씀, 금리를 바꿔
     * changed가 기본금리를 안 씀)도 이 비교에 쓰였으므로 남긴다. 그래야 이 행만으로 두 결과를 다시 계산할 수 있다.
     */
    private static AppliedDataVersions merge(AppliedDataVersions current, AppliedDataVersions changed) {
        return new AppliedDataVersions(
                Optional.ofNullable(current.savingsRateVersionId()).orElse(changed.savingsRateVersionId()),
                Optional.ofNullable(current.sp500VersionId()).orElse(changed.sp500VersionId()),
                current.calcParamVersionId(), current.engineVersion());
    }

    private static CalcResult withVersions(CalcResult result, AppliedDataVersions versions) {
        return new CalcResult(result.status(), result.goalAmount(), result.calcStartMonth(), result.targetMonth(),
                result.baselineScenario(), result.scenarios(), result.requiredMonthlySaving(), result.assumptions(),
                versions);
    }

    /** current·changed는 공유 빌더(#28)로, diff만 여기서 방향 단어로 만든다(계약 C2-6, D-055). */
    private Map<String, String> buildDisplay(CalcComparison comparison, ConditionResolver.ResolvedConditions current,
            ConditionResolver.ResolvedConditions changed) {
        Map<String, String> display = new LinkedHashMap<>();
        display.putAll(displayBuilder.build(current.input(), comparison.current(), CURRENT, CONDITIONS_CURRENT));
        display.putAll(displayBuilder.build(changed.input(), comparison.changed(), CHANGED, CONDITIONS_CHANGED));
        for (CalcComparison.ScenarioDiff diff : comparison.diff().byScenario()) {
            String prefix = "diff." + diff.scenario() + ".";
            display.put(prefix + "afterTaxAssetDelta", formatter.formatDifference(diff.afterTaxAssetDelta()));
            display.put(prefix + "principalDelta", formatter.formatDifference(diff.principalDelta()));
            display.put(prefix + "afterTaxGainDelta", formatter.formatDifference(diff.afterTaxGainDelta()));
        }
        return display;
    }

    /**
     * @param calcRunId 이번 계산의 calc_run(CHAT) id
     * @param comparison 비교 결과
     * @param display 표시 문자열 맵
     */
    public record ComparisonResult(long calcRunId, CalcComparison comparison, Map<String, String> display) {

        public ComparisonResult {
            display = Map.copyOf(display);
        }
    }
}
