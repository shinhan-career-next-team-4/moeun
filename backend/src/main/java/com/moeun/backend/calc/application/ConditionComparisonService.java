package com.moeun.backend.calc.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.calc.CalcRejectedException;
import com.moeun.backend.calc.ChatComparisonView;
import com.moeun.backend.calc.ConditionComparisonQuery;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.plan.PlanView;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.EtfSeries;
import com.moeun.backend.refdata.ReferenceDataService;
import com.moeun.backend.refdata.SavingsAvgRateData;

/**
 * 조건 변경 비교(FR-CMP-02, #64). {@link CompareConditionsUseCase#compare}와 같은 준비(현재 계획·계산 파라미터·ETF 종목·
 * 기본금리)를 하고 같은 계산 {@link CompareConditionsUseCase#computeComparison}을 부르되 calc_run(CHAT)을 남기지 않는다.
 * 그래서 읽기 전용 트랜잭션이다.
 */
@Service
class ConditionComparisonService implements ConditionComparisonQuery {

    private final PlanSaveService planSaveService;
    private final ReferenceDataService referenceDataService;
    private final CompareConditionsUseCase compareConditions;

    ConditionComparisonService(PlanSaveService planSaveService, ReferenceDataService referenceDataService,
            CompareConditionsUseCase compareConditions) {
        this.planSaveService = planSaveService;
        this.referenceDataService = referenceDataService;
        this.compareConditions = compareConditions;
    }

    @Override
    @Transactional(readOnly = true)
    public ChatComparisonView compareWithCurrentPlan(long memberId, ConditionOverride changes) {
        PlanView plan = planSaveService.findCurrent(memberId).orElseThrow(CalcRejectedException::noPlan);
        CalcParams params = referenceDataService.findActiveCalcParams()
                .orElseThrow(() -> new IllegalStateException("active calc params are not loaded"));
        EtfSeries etf = referenceDataService.findActiveEtfSeries(EtfProductSelector.productCodeOf(plan)).orElse(null);
        SavingsAvgRateData defaultRate = referenceDataService.findActiveSavingsAvgRate().orElse(null);

        CompareConditionsUseCase.ComputedComparison computed = compareConditions.computeComparison(plan, changes,
                params, etf, etf, defaultRate);
        return new ChatComparisonView(plan.currentVersionId(), computed.comparison(), computed.display());
    }
}
