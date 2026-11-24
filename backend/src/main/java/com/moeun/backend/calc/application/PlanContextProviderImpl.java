package com.moeun.backend.calc.application;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.ChatComparisonQuery;
import com.moeun.backend.calc.ChatComparisonView;
import com.moeun.backend.calc.CurrentPlanResultService;
import com.moeun.backend.calc.PlanContextProvider;
import com.moeun.backend.calc.PlanContextView;
import com.moeun.backend.calc.PlanRunRef;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.domain.CalcRun;
import com.moeun.backend.calc.domain.CalcRunRepository;
import com.moeun.backend.calc.domain.RunType;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.plan.PlanView;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.EtfSeries;
import com.moeun.backend.refdata.ReferenceDataService;
import com.moeun.backend.refdata.SavingsAvgRateData;

import tools.jackson.databind.JsonNode;

/**
 * planContext(계약 C1a-10)와 대화 비교 기록 재계산(H-8b, C12b-6)을 만든다.
 * <p>두 공개 API가 같은 "기록 재현" 코드를 쓴다: calc_run 조회 → plan 공개 API로 소유권 확인 → 그 기록이 남긴 기준 데이터
 * 버전으로 데이터 조회 → 다시 계산. 결과는 저장하지 않는다(ADR-002). 엔진은 지금 엔진 하나뿐이라 기록의 engine_version과
 * 다르면 지금 엔진으로 계산하고, 결과의 {@code dataVersions.engineVersion}에 실제 쓴 엔진이 드러난다.
 * <p>현재 계획 경로({@link #findForCurrentPlan})만 calc_run(PLAN)을 기록할 수 있어 읽기 전용 트랜잭션으로 열지 않는다.
 * 나머지는 읽기만 한다.
 */
@Service
public class PlanContextProviderImpl implements PlanContextProvider, ChatComparisonQuery {

    private static final String NO_PREFIX = "";
    private static final String CONDITIONS_PREFIX = "conditions.";

    private final CalcRunRepository calcRunRepository;
    private final PlanSaveService planSaveService;
    private final ReferenceDataService referenceDataService;
    private final ConditionResolver conditionResolver;
    private final CalcEngine calcEngine;
    private final ResultDisplayBuilder displayBuilder;
    private final CurrentPlanResultService currentPlanResultService;
    private final CompareConditionsUseCase compareConditionsUseCase;

    public PlanContextProviderImpl(CalcRunRepository calcRunRepository, PlanSaveService planSaveService,
            ReferenceDataService referenceDataService, ConditionResolver conditionResolver, CalcEngine calcEngine,
            ResultDisplayBuilder displayBuilder, CurrentPlanResultService currentPlanResultService,
            CompareConditionsUseCase compareConditionsUseCase) {
        this.calcRunRepository = calcRunRepository;
        this.planSaveService = planSaveService;
        this.referenceDataService = referenceDataService;
        this.conditionResolver = conditionResolver;
        this.calcEngine = calcEngine;
        this.displayBuilder = displayBuilder;
        this.currentPlanResultService = currentPlanResultService;
        this.compareConditionsUseCase = compareConditionsUseCase;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanRunRef> findPlanRun(long memberId, long calcRunId) {
        // findVersion이 그 버전 행과 현재 계획 행을 함께 읽으므로 기준 시나리오는 조회 시점의 plan.baseline_scenario다
        return findOwnedRun(memberId, calcRunId, RunType.PLAN)
                .map(owned -> new PlanRunRef(calcRunId, owned.run().getPlanVersionId(),
                        Scenario.valueOf(owned.plan().baselineScenario())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanContextView> findForCalcRun(long memberId, long calcRunId, Scenario baselineScenario) {
        Objects.requireNonNull(baselineScenario, "baselineScenario");
        return findOwnedRun(memberId, calcRunId, RunType.PLAN).map(owned -> {
            RecordedData data = loadRecordedData(owned.run());
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

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findChatRunPlanVersion(long memberId, long calcRunId) {
        return findOwnedRun(memberId, calcRunId, RunType.CHAT).map(owned -> owned.run().getPlanVersionId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ChatComparisonView> recompute(long memberId, long calcRunId,
            Map<String, JsonNode> conditionChanges) {
        Objects.requireNonNull(conditionChanges, "conditionChanges");
        return findOwnedRun(memberId, calcRunId, RunType.CHAT).map(owned -> {
            ConditionChanges.Conversion changes = toConversion(conditionChanges);
            // calc_run에는 기준 시나리오가 없어 지금 계획의 기준 시나리오를 쓴다. 해커톤 범위에는 기준 시나리오를 바꾸는
            // 기능이 없어 /internal/calc 때와 같다. 그 기능이 생기면 대화 메시지에 저장한 값을 인자로 받아야 한다
            RecordedData data = loadRecordedData(owned.run());
            CompareConditionsUseCase.ComputedComparison computed = compareConditionsUseCase.computeComparison(
                    owned.plan(), changes.override(), data.params(), data.sp500(), data.changedEtf(),
                    changes.etfProductCode(), data.defaultRate());
            return new ChatComparisonView(owned.run().getPlanVersionId(), computed.comparison(), computed.display());
        });
    }

    /**
     * 소유권 확인 2단계(C1a-10). ① 종류까지 맞는 calc_run ② 그 계획 버전이 이 회원의 것인지 plan 공개 API로 확인.
     * 없는 기록·남의 기록·다른 종류의 기록은 모두 빈 값이다.
     */
    private Optional<OwnedRun> findOwnedRun(long memberId, long calcRunId, RunType runType) {
        return calcRunRepository.findByIdAndRunType(calcRunId, runType)
                .flatMap(run -> planSaveService.findVersion(memberId, run.getPlanVersionId())
                        .map(plan -> new OwnedRun(run, plan)));
    }

    /** 기록이 남긴 버전의 기준 데이터. 기록에 없는 데이터(ETF 0%의 S&P500, 직접 넣은 금리의 평균금리)는 쓰지 않았다. */
    private RecordedData loadRecordedData(CalcRun run) {
        CalcParams params = referenceDataService.requireCalcParams(run.getCalcParamVersionId());
        EtfSeries sp500 = run.getSp500VersionId() == null
                ? null : referenceDataService.requireEtfSeries(run.getSp500VersionId());
        SavingsAvgRateData defaultRate = run.getSavingsRateVersionId() == null
                ? null : referenceDataService.requireSavingsAvgRate(run.getSavingsRateVersionId());
        // CHAT 기록의 changed가 다른 종목이었으면 그 버전, 아니면 current와 같은 종목(#157)
        EtfSeries changedEtf = run.getChangedEtfVersionId() == null
                ? sp500 : referenceDataService.requireEtfSeries(run.getChangedEtfVersionId());
        return new RecordedData(params, sp500, changedEtf, defaultRate);
    }

    private static PlanView withBaselineScenario(PlanView plan, Scenario baselineScenario) {
        return new PlanView(plan.currentVersionId(), plan.goalType(), plan.goalCustomName(), plan.goalAmount(),
                plan.targetPeriodMonths(), plan.heldAssets(), plan.monthlySavingAmount(), plan.savingsRateInputPct(),
                plan.savingsRatioPct(), plan.etfProductCode(), plan.calcStartMonth(), baselineScenario.name(),
                plan.revision(),
                plan.canUndo(), plan.updatedAt());
    }

    /**
     * 저장된 대화의 바꾼 조건을 타입만 바꾼다. 범위는 검사하지 않는다(C12b-6). null 값은 바꾸지 않은 것으로 본다.
     * 변환은 {@code /internal/calc}와 같은 {@link ConditionChanges}를 쓰고, 맞지 않는 값은 모두 예외로 낸다.
     *
     * @throws IllegalArgumentException 허용 6개 밖의 키, 타입이 맞지 않는 값, 정수 자리의 소수, 타입 범위를 넘는 정수
     */
    private static ConditionChanges.Conversion toConversion(Map<String, JsonNode> conditionChanges) {
        Map<String, JsonNode> changes = ConditionChanges.withoutNulls(conditionChanges);
        if (!ConditionChanges.unchangeableKeys(changes).isEmpty()) {
            throw new IllegalArgumentException("condition is not changeable: "
                    + ConditionChanges.unchangeableKeys(changes));
        }
        if (!ConditionChanges.allValuesWellTyped(changes)) {
            throw new IllegalArgumentException("condition value has a wrong type");
        }
        ConditionChanges.Conversion conversion = ConditionChanges.convert(changes);
        if (!conversion.isExact()) {
            throw new IllegalArgumentException("condition value cannot be converted: "
                    + conversion.notIntegerKeys() + conversion.outOfTypeRangeKeys());
        }
        return conversion;
    }

    private record OwnedRun(CalcRun run, PlanView plan) {
    }

    private record RecordedData(CalcParams params, EtfSeries sp500, EtfSeries changedEtf,
            SavingsAvgRateData defaultRate) {
    }
}
