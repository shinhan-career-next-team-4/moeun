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
import com.moeun.backend.refdata.EtfSeries;
import com.moeun.backend.refdata.ReferenceDataService;
import com.moeun.backend.refdata.SavingsAvgRateData;

/**
 * 대화 재계산 유스케이스(계약 internal-calc C2-2 검사 순서 7~9, C2-7, C2-8).
 * <p>{@link #compare}의 기준은 그 회원의 현재 계획이고, 이번 요청의 바꾼 조건만 얹는다(이전 변경은 쌓지 않음, D-041).
 * 시작월은 기준 계획의 값이다. current와 changed는 같은 기준 데이터·엔진 버전으로 계산하고 그 버전으로 calc_run(CHAT)
 * 한 행을 남긴다. display도 같은 트랜잭션 안에서 만들므로 어느 단계에서 실패해도 기록이 함께 롤백된다.
 * <p>계산 부분({@link #computeComparison})은 기록하지 않고, 저장된 대화 기록을 다시 계산하는 쪽(#78)도 같이 쓴다.
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
        // 계획의 ETF 종목(#150). 대화로 종목을 바꾸는 비교는 후속(대화 조건에 종목 추가)에서 한다
        EtfSeries sp500 = referenceDataService.findActiveEtfSeries(EtfProductSelector.productCodeOf(plan))
                .orElse(null);
        SavingsAvgRateData defaultRate = referenceDataService.findActiveSavingsAvgRate().orElse(null);

        ComputedComparison computed = computeComparison(plan, changes, params, sp500, defaultRate);
        long calcRunId = calcRunRecorder.recordChatRun(plan.currentVersionId(), computed.versions());
        return new ComparisonResult(calcRunId, computed.comparison(), computed.display());
    }

    /**
     * 기록하지 않고 비교만 계산한다. 이 요청({@link #compare})과, 저장된 대화 기록을 그때의 데이터로 다시 계산하는
     * planContext 쪽(#78 {@code ChatComparisonQuery.recompute})이 같은 코드를 써서 두 결과가 글자까지 같다.
     *
     * @param plan 비교의 기준 계획(현재 계획, 또는 대화 기록의 계획 버전)
     * @param changes 바꾼 조건
     * @param params 계산 파라미터
     * @param sp500 S&P500 월말 종가. ETF가 없으면 null이어도 된다
     * @param defaultRate 적금 평균금리. 기본금리가 필요 없으면 null이어도 된다
     * @throws CalcRejectedException 이미 목표 초과(409 ALREADY_EXCEEDED)
     */
    public ComputedComparison computeComparison(PlanView plan, ConditionOverride changes, CalcParams params,
            EtfSeries sp500, SavingsAvgRateData defaultRate) {
        ConditionResolver.ResolvedConditions current = conditionResolver.resolve(plan, defaultRate, params);
        ConditionResolver.ResolvedConditions changed = conditionResolver.resolveChanged(plan, changes, defaultRate,
                params);
        if (current.input().hasExceededGoal() && changed.input().hasExceededGoal()) {
            // 바꾼 조건으로도 여전히 초과면 비교 결과 없이 409 ALREADY_EXCEEDED(D-092, C3-2)
            throw CalcRejectedException.alreadyExceeded();
        }
        CalcResult currentResult = calcEngine.calculate(current, params, sp500);
        CalcResult changedResult = calcEngine.calculate(changed, params, sp500);
        AppliedDataVersions versions = merge(currentResult.dataVersions(), changedResult.dataVersions());
        currentResult = withVersions(currentResult, versions);
        changedResult = withVersions(changedResult, versions);

        CalcComparison comparison = CalcComparison.of(currentResult, changedResult, current.input(),
                changed.input());
        return new ComputedComparison(comparison, versions, buildDisplay(comparison, current, changed, changes));
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

    /** current·changed는 공유 빌더(#28)로, diff만 여기서 방향 단어로 만든다(계약 C2-6, D-055, D-092). */
    private Map<String, String> buildDisplay(CalcComparison comparison, ConditionResolver.ResolvedConditions current,
            ConditionResolver.ResolvedConditions changed, ConditionOverride changes) {
        boolean goalChanged = changes.goalAmount() != null;
        Map<String, String> display = new LinkedHashMap<>();
        display.putAll(displayBuilder.build(current.input(), comparison.current(), CURRENT, CONDITIONS_CURRENT,
                goalChanged, !goalChanged));
        display.putAll(displayBuilder.build(changed.input(), comparison.changed(), CHANGED, CONDITIONS_CHANGED,
                goalChanged, !goalChanged));
        for (CalcComparison.ScenarioDiff diff : comparison.diff().byScenario()) {
            String prefix = "diff." + diff.scenario() + ".";
            display.put(prefix + "afterTaxAssetDelta", formatter.formatDifference(diff.afterTaxAssetDelta()));
            display.put(prefix + "principalDelta", formatter.formatDifference(diff.principalDelta()));
            display.put(prefix + "afterTaxGainDelta", formatter.formatDifference(diff.afterTaxGainDelta()));
        }
        return display;
    }

    /**
     * 기록 전의 비교 결과.
     *
     * @param comparison 비교 결과
     * @param versions current·changed에 함께 쓴 기준 데이터·엔진 버전(calc_run(CHAT)에 기록할 값)
     * @param display 표시 문자열 맵
     */
    public record ComputedComparison(CalcComparison comparison, AppliedDataVersions versions,
            Map<String, String> display) {

        public ComputedComparison {
            display = Map.copyOf(display);
        }
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
