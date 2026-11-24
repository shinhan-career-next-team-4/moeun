package com.moeun.backend.calc.application;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.CurrentPlanResultService;
import com.moeun.backend.calc.PlanContextProvider;
import com.moeun.backend.calc.PlanContextView;
import com.moeun.backend.calc.PlanRunRef;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.domain.RunType;
import com.moeun.backend.plan.PlanView;

/**
 * planContext(계약 C1a-10). 기록 경로는 그 계산 기록을 다시 계산해 만들고, 결과는 저장하지 않는다(ADR-002). 엔진은 지금 엔진
 * 하나뿐이라 기록의 engine_version과 다르면 지금 엔진으로 계산하고, 결과의 {@code dataVersions.engineVersion}에 실제 쓴
 * 엔진이 드러난다.
 * <p>현재 계획 경로({@link #findForCurrentPlan})만 calc_run(PLAN)을 기록할 수 있어 읽기 전용 트랜잭션으로 열지 않는다.
 * 나머지는 읽기만 한다.
 */
@Service
public class RecalculatingPlanContextProvider implements PlanContextProvider {

    private static final String NO_PREFIX = "";
    private static final String CONDITIONS_PREFIX = "conditions.";

    private final CalcRunReplayer replayer;
    private final ConditionResolver conditionResolver;
    private final CalcEngine calcEngine;
    private final ResultDisplayBuilder displayBuilder;
    private final CurrentPlanResultService currentPlanResultService;

    RecalculatingPlanContextProvider(CalcRunReplayer replayer, ConditionResolver conditionResolver,
            CalcEngine calcEngine, ResultDisplayBuilder displayBuilder,
            CurrentPlanResultService currentPlanResultService) {
        this.replayer = replayer;
        this.conditionResolver = conditionResolver;
        this.calcEngine = calcEngine;
        this.displayBuilder = displayBuilder;
        this.currentPlanResultService = currentPlanResultService;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanRunRef> findPlanRun(long memberId, long calcRunId) {
        // findVersion이 그 버전 행과 현재 계획 행을 함께 읽으므로 기준 시나리오는 조회 시점의 plan.baseline_scenario다
        return replayer.findOwnedRun(memberId, calcRunId, RunType.PLAN)
                .map(owned -> new PlanRunRef(calcRunId, owned.run().getPlanVersionId(),
                        Scenario.valueOf(owned.plan().baselineScenario())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanContextView> findForCalcRun(long memberId, long calcRunId, Scenario baselineScenario) {
        Objects.requireNonNull(baselineScenario, "baselineScenario");
        return replayer.findOwnedRun(memberId, calcRunId, RunType.PLAN).map(owned -> {
            CalcRunReplayer.RecordedData data = replayer.loadRecordedData(owned.run());
            // calc_run에 기준 시나리오가 없어 chat이 F-3 때 저장한 값을 쓴다. 그 뒤 계획의 기준 시나리오가 바뀌어도
            // ChatRequest.baselineScenario와 planContext.result.baselineScenario가 어긋나지 않는다
            PlanView plan = withBaselineScenario(owned.plan(), baselineScenario);
            ConditionResolver.ResolvedConditions resolved = conditionResolver.resolve(plan, data.defaultRate(),
                    data.params());
            CalcResult result = calcEngine.calculate(resolved, data.params(), data.sp500());
            Map<String, String> display = displayBuilder.build(resolved.input(), result, NO_PREFIX,
                    CONDITIONS_PREFIX);
            return PlanContextView.of(calcRunId, resolved.input(), plan.etfProductCode(), result, display);
        });
    }

    @Override
    @Transactional
    public Optional<PlanContextView> findForCurrentPlan(long memberId) {
        // 결과 화면과 같은 서비스를 그대로 부른다. 같은 계획 버전이면 기록을 다시 남기지 않고(G-18), 동시에 불려도
        // 그 서비스의 회원 단위 잠금으로 기록은 하나다(C12a-6-6)
        return currentPlanResultService.findForCurrentPlan(memberId)
                .map(current -> PlanContextView.of(current.calcRunId(), current.input(),
                        current.plan().etfProductCode(), current.result(), current.display()));
    }

    private static PlanView withBaselineScenario(PlanView plan, Scenario baselineScenario) {
        return new PlanView(plan.currentVersionId(), plan.goalType(), plan.goalCustomName(), plan.goalAmount(),
                plan.targetPeriodMonths(), plan.heldAssets(), plan.monthlySavingAmount(), plan.savingsRateInputPct(),
                plan.savingsRatioPct(), plan.etfProductCode(), plan.calcStartMonth(), baselineScenario.name(),
                plan.revision(),
                plan.canUndo(), plan.updatedAt());
    }
}
