package com.moeun.backend.calc.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.CalcStatus;
import com.moeun.backend.calc.ScenarioResult;
import com.moeun.backend.calc.application.AlternativeView.Alternative;
import com.moeun.backend.calc.application.CompareConditionsUseCase.ComputedComparison;
import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.ConditionValidator;
import com.moeun.backend.plan.ConditionViolationException;
import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.NoPlanException;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.plan.PlanView;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.EtfSeries;
import com.moeun.backend.refdata.ReferenceDataService;
import com.moeun.backend.refdata.SavingsAvgRateData;

/**
 * 대안 값 산출(FR-ALT-01·03). 기준 시나리오로 목표에 못 미치는 계획만 대안을 만들고, 대안마다 조건 하나만 바꿔
 * {@link CompareConditionsUseCase#computeComparison}(#32)으로 계산한다. 결과는 저장하지 않는다.
 * <p>월 저축액·비중의 후보값은 {@link AlternativeCandidatePolicy}가 정한다. 기한은 사용자가 입력한 기간이다.
 */
@Service
class AlternativeFinder implements AlternativeQuery {

    private final PlanSaveService planSaveService;
    private final ReferenceDataService referenceDataService;
    private final ConditionResolver conditionResolver;
    private final CalcEngine calcEngine;
    private final CompareConditionsUseCase compareConditions;
    private final ConditionValidator conditionValidator;
    private final AlternativeCandidatePolicy policy;

    AlternativeFinder(PlanSaveService planSaveService, ReferenceDataService referenceDataService,
            ConditionResolver conditionResolver, CalcEngine calcEngine, CompareConditionsUseCase compareConditions,
            ConditionValidator conditionValidator, AlternativeCandidatePolicy policy) {
        this.planSaveService = planSaveService;
        this.referenceDataService = referenceDataService;
        this.conditionResolver = conditionResolver;
        this.calcEngine = calcEngine;
        this.compareConditions = compareConditions;
        this.conditionValidator = conditionValidator;
        this.policy = policy;
    }

    @Override
    @Transactional(readOnly = true)
    public AlternativeView find(long memberId, Integer desiredTargetPeriodMonths) {
        validatePeriod(desiredTargetPeriodMonths);
        PlanView plan = planSaveService.findCurrent(memberId).orElseThrow(NoPlanException::new);
        CalcParams params = referenceDataService.findActiveCalcParams()
                .orElseThrow(() -> new IllegalStateException("active calc params are not loaded"));
        EtfSeries etf = referenceDataService.findActiveEtfSeries(EtfProductSelector.productCodeOf(plan)).orElse(null);
        SavingsAvgRateData defaultRate = referenceDataService.findActiveSavingsAvgRate().orElse(null);

        ConditionResolver.ResolvedConditions resolved = conditionResolver.resolve(plan, defaultRate, params);
        CalcInput input = resolved.input();
        CalcResult current = calcEngine.calculate(resolved, params, etf);
        if (isAchieved(current)) {
            return AlternativeView.notNeeded();
        }

        List<Alternative> alternatives = new ArrayList<>();
        for (AlternativeKey key : AlternativeKey.values()) {
            Optional<Number> value = candidateValue(key, input, desiredTargetPeriodMonths);
            alternatives.add(value.map(changed -> compute(key, changed, plan, params, etf, defaultRate))
                    .orElseGet(() -> Alternative.empty(key)));
        }
        return new AlternativeView(true, alternatives);
    }

    /** 기한을 늘리는 입력의 범위만 본다(CP-02). 값이 없으면 검사할 것이 없다. */
    private void validatePeriod(Integer months) {
        if (months == null) {
            return;
        }
        List<FieldError> errors = conditionValidator.validate(ChangePath.ALTERNATIVE,
                new ConditionOverride(null, months, null, null, null, null));
        if (!errors.isEmpty()) {
            throw new ConditionViolationException(errors);
        }
    }

    /** 이미 목표를 넘었거나(계산 안 함) 기준 시나리오 판정이 달성이면 대안이 필요 없다. */
    private static boolean isAchieved(CalcResult current) {
        return current.status() == CalcStatus.ALREADY_EXCEEDED
                || current.findBaselineResult().map(ScenarioResult::isAchieved).orElse(false);
    }

    private Optional<Number> candidateValue(AlternativeKey key, CalcInput input, Integer months) {
        return switch (key) {
            case MONTHLY_SAVING_AMOUNT -> policy.monthlySavingAmount(input).map(Number.class::cast);
            case TARGET_PERIOD_MONTHS -> Optional.ofNullable(months).map(Number.class::cast);
            case SAVINGS_RATIO_PCT -> policy.savingsRatioPct(input).map(Number.class::cast);
        };
    }

    private Alternative compute(AlternativeKey key, Number value, PlanView plan, CalcParams params, EtfSeries etf,
            SavingsAvgRateData defaultRate) {
        ComputedComparison computed = compareConditions.computeComparison(plan, onlyChanged(key, value), params, etf,
                etf, defaultRate);
        return new Alternative(key, value, computed.comparison(), computed.display());
    }

    /** 한 조건만 담은 바꾼 조건. 나머지는 null이라 현재 계획 그대로다(FR-ALT-03). */
    private static ConditionOverride onlyChanged(AlternativeKey key, Number value) {
        return switch (key) {
            case MONTHLY_SAVING_AMOUNT -> new ConditionOverride(value.longValue(), null, null, null, null, null);
            case TARGET_PERIOD_MONTHS -> new ConditionOverride(null, value.intValue(), null, null, null, null);
            case SAVINGS_RATIO_PCT -> new ConditionOverride(null, null, value.intValue(), null, null, null);
        };
    }
}
