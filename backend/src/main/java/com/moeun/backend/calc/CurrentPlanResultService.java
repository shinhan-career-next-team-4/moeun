package com.moeun.backend.calc;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.calc.application.CalcEngine;
import com.moeun.backend.calc.application.CalcRunRecorder;
import com.moeun.backend.calc.application.ConditionResolver;
import com.moeun.backend.calc.application.ResultDisplayBuilder;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.plan.PlanView;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.EtfSeries;
import com.moeun.backend.refdata.ReferenceDataService;
import com.moeun.backend.refdata.SavingsAvgRateData;

/**
 * 현재 계획의 결과를 조회하고 calc_run(PLAN)을 "없으면 기록"한다(#29, ERD Q1, 계약 G-18, C12a-6-3).
 * <p>결과는 저장하지 않고 매번 활성 기준 데이터로 다시 계산한다(ADR-002). 같은 계획 버전·기준 데이터 버전·엔진 버전이면
 * 같은 calcRunId를 돌려줘서, 프론트가 설명을 두 번 요청하지 않게 한다.
 * <p>두 메서드 모두 기록(INSERT)을 할 수 있으므로 읽기 전용 트랜잭션으로 열지 않는다.
 */
@Service
public class CurrentPlanResultService {

    private static final String NO_PREFIX = "";
    private static final String CONDITIONS_PREFIX = "conditions.";

    private final PlanSaveService planSaveService;
    private final ReferenceDataService referenceDataService;
    private final ConditionResolver conditionResolver;
    private final CalcEngine calcEngine;
    private final CalcRunRecorder calcRunRecorder;
    private final ResultDisplayBuilder displayBuilder;

    public CurrentPlanResultService(PlanSaveService planSaveService, ReferenceDataService referenceDataService,
            ConditionResolver conditionResolver, CalcEngine calcEngine, CalcRunRecorder calcRunRecorder,
            ResultDisplayBuilder displayBuilder) {
        this.planSaveService = planSaveService;
        this.referenceDataService = referenceDataService;
        this.conditionResolver = conditionResolver;
        this.calcEngine = calcEngine;
        this.calcRunRecorder = calcRunRecorder;
        this.displayBuilder = displayBuilder;
    }

    /**
     * 현재 계획을 계산해 결과와 display를 돌려준다. 그 버전의 기록이 없으면 이번 계산을 기록한다.
     * 호출자 트랜잭션에 합류하고, 없으면 새로 연다(입력 저장 유스케이스는 저장과 같은 트랜잭션에서 부른다).
     *
     * @param memberId 회원
     * @return 계획이 없으면 빈 값(P-4가 409 NO_PLAN으로 바꾼다)
     * @throws IllegalStateException 활성 기준 데이터(계산 파라미터, 기본금리가 필요할 때 평균금리)가 없을 때
     */
    @Transactional
    public Optional<CurrentPlanResult> findForCurrentPlan(long memberId) {
        Optional<PlanView> current = planSaveService.findCurrent(memberId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        PlanView plan = current.get();
        ActiveData data = loadActiveData();
        ConditionResolver.ResolvedConditions resolved = conditionResolver.resolve(plan, data.defaultRate(),
                data.params());
        CalcResult result = calcEngine.calculate(resolved, data.params(), data.sp500());
        long calcRunId = calcRunRecorder.recordPlanRunIfAbsent(memberId, plan.currentVersionId(),
                result.dataVersions());
        Map<String, String> display = displayBuilder.build(resolved.input(), result, NO_PREFIX, CONDITIONS_PREFIX);
        return Optional.of(new CurrentPlanResult(plan, calcRunId, resolved.input(), result, display));
    }

    /**
     * 현재 계획 버전의 calc_run(PLAN)만 "없으면 기록"한다. 계산(부트스트랩)과 display는 만들지 않는다.
     * PlanChanged 리스너가 쓴다. 같은 이벤트를 두 번 받아도 기록은 하나다.
     *
     * @param memberId 회원
     * @return 기록 id(calcRunId). 계획이 없으면(그 사이 지워짐) 빈 값
     */
    @Transactional
    public Optional<Long> recordForCurrentPlan(long memberId) {
        Optional<PlanView> current = planSaveService.findCurrent(memberId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        PlanView plan = current.get();
        ActiveData data = loadActiveData();
        ConditionResolver.ResolvedConditions resolved = conditionResolver.resolve(plan, data.defaultRate(),
                data.params());
        AppliedDataVersions versions = calcEngine.determineDataVersions(resolved, data.params(), data.sp500());
        return Optional.of(calcRunRecorder.recordPlanRunIfAbsent(memberId, plan.currentVersionId(), versions));
    }

    private ActiveData loadActiveData() {
        CalcParams params = referenceDataService.findActiveCalcParams()
                .orElseThrow(() -> new IllegalStateException("active calc params are not loaded"));
        // S&P500은 레포에 없어 각자 적재한다(A안). 없으면 ETF가 든 계획만 엔진이 이유를 담아 막는다
        EtfSeries sp500 = referenceDataService.findRepresentativeEtfSeries().orElse(null);
        SavingsAvgRateData defaultRate = referenceDataService.findActiveSavingsAvgRate().orElse(null);
        return new ActiveData(params, sp500, defaultRate);
    }

    private record ActiveData(CalcParams params, EtfSeries sp500, SavingsAvgRateData defaultRate) {
    }
}
