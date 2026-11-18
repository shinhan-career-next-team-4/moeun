package com.moeun.backend.plan.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.ConditionValidator;
import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.PlanChanged;
import com.moeun.backend.plan.domain.Plan;
import com.moeun.backend.plan.domain.PlanRepository;
import com.moeun.backend.plan.domain.PlanVersion;
import com.moeun.backend.plan.domain.PlanVersionRepository;

/** 내 계획 저장(FR-INP-10), 적용(FR-PLAN-01~03), 되돌리기(FR-PLAN-04). 모든 조회·갱신은 memberId 조건을 붙인다. */
@Service
@Transactional
public class PlanService {

    private static final String DEFAULT_BASELINE = "AVG";   // TBD-40: 기본값 평균

    private final PlanRepository planRepository;
    private final PlanVersionRepository versionRepository;
    private final PlanInputValidator inputValidator;
    private final ConditionValidator conditionValidator;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PlanService(PlanRepository planRepository, PlanVersionRepository versionRepository,
            PlanInputValidator inputValidator, ConditionValidator conditionValidator,
            ApplicationEventPublisher events, Clock clock) {
        this.planRepository = planRepository;
        this.versionRepository = versionRepository;
        this.inputValidator = inputValidator;
        this.conditionValidator = conditionValidator;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<PlanView> find(long memberId) {
        return planRepository.findById(memberId).map(this::toView);
    }

    public PlanView save(long memberId, PlanInput in) {
        throwIfInvalid(inputValidator.validate(in));
        Optional<Plan> existing = planRepository.findById(memberId);
        LocalDate calcStartMonth = existing.map(plan -> version(memberId, plan.getCurrentVersionId()))
                .map(PlanVersion::getCalcStartMonth)
                .orElseGet(() -> YearMonth.now(clock).atDay(1));   // TBD-38: 처음 저장한 달로 고정
        Integer savingsRatioPct = existing.map(plan -> version(memberId, plan.getCurrentVersionId()))
                .map(PlanVersion::getSavingsRatioPct).orElse(null);   // 요청에 없는 값은 현재 버전에서 복사(C12a-5-1)
        PlanVersion saved = versionRepository.save(newVersion(memberId, in, savingsRatioPct,
                calcStartMonth, "INPUT"));
        Long before = existing.map(Plan::getCurrentVersionId).orElse(null);
        if (existing.isEmpty()) {
            planRepository.save(new Plan(memberId, saved.getId(), DEFAULT_BASELINE));
        } else {
            planRepository.overwriteCurrent(memberId, saved.getId());
        }
        return publishAndView(memberId, "INPUT", before, saved.getId());
    }

    public PlanView apply(long memberId, ChangePath path, ConditionOverride changes, long expectedRevision) {
        Plan plan = planRepository.findById(memberId).orElseThrow(
                () -> new PlanConflictException(PlanConflictException.PLAN_CHANGED, Optional.empty()));
        throwIfInvalid(conditionValidator.validate(path, changes));
        PlanVersion current = version(memberId, plan.getCurrentVersionId());
        PlanInput merged = merge(current, changes);
        throwIfInvalid(inputValidator.validate(merged));
        Integer ratio = changes.savingsRatioPct() != null ? changes.savingsRatioPct() : current.getSavingsRatioPct();
        PlanVersion saved = versionRepository.save(
                newVersion(memberId, merged, ratio, current.getCalcStartMonth(), path.name()));
        if (planRepository.replaceCurrent(memberId, saved.getId(), expectedRevision) == 0) {
            throw new PlanConflictException(PlanConflictException.PLAN_CHANGED, find(memberId));
        }
        return publishAndView(memberId, "APPLY", current.getId(), saved.getId());
    }

    public PlanView undo(long memberId, long expectedRevision) {
        Plan plan = planRepository.findById(memberId).orElseThrow(
                () -> new PlanConflictException(PlanConflictException.PLAN_CHANGED, Optional.empty()));
        if (planRepository.restorePrevious(memberId, expectedRevision) == 0) {
            throw new PlanConflictException(PlanConflictException.PLAN_CHANGED, find(memberId));
        }
        return publishAndView(memberId, "UNDO", plan.getCurrentVersionId(), plan.getPreviousVersionId());
    }

    private PlanView publishAndView(long memberId, String action, Long beforeVersionId, long afterVersionId) {
        PlanView view = find(memberId).orElseThrow();
        events.publishEvent(new PlanChanged(memberId, action, beforeVersionId, afterVersionId, view.revision()));
        return view;
    }

    private static void throwIfInvalid(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new PlanValidationException(errors);
        }
    }

    private PlanVersion version(long memberId, long versionId) {
        return versionRepository.findByIdAndMemberId(versionId, memberId).orElseThrow();
    }

    /** 현재 버전에 바뀐 값만 덮는다. */
    private static PlanInput merge(PlanVersion current, ConditionOverride changes) {
        return new PlanInput(
                current.getGoalType(),
                current.getGoalCustomName(),
                current.getGoalAmount(),
                firstNonNull(changes.targetPeriodMonths(), current.getTargetPeriodMonths()),
                current.getHeldAssets(),
                firstNonNull(changes.monthlySaving(), current.getMonthlySaving()),
                firstNonNull(changes.savingsRatePct(), current.getSavingsRatePct()));
    }

    private PlanVersion newVersion(long memberId, PlanInput in, Integer savingsRatioPct, LocalDate calcStartMonth,
            String source) {
        String customName = in.goalCustomName() == null ? null : in.goalCustomName().strip();
        return new PlanVersion(memberId, in.goalType(),
                in.goalType() == com.moeun.backend.plan.GoalType.CUSTOM ? customName : null,
                in.goalAmount(), in.targetPeriodMonths(), calcStartMonth, in.heldAssets(), in.monthlySavingAmount(),
                in.savingsRateInputPct(), savingsRatioPct, source, clock.instant());
    }

    private PlanView toView(Plan plan) {
        PlanVersion version = version(plan.getMemberId(), plan.getCurrentVersionId());
        return new PlanView(version.getGoalType(), version.getGoalCustomName(), version.getGoalAmount(),
                version.getTargetPeriodMonths(), version.getHeldAssets(), version.getMonthlySaving(),
                version.getSavingsRatePct(),
                version.getSavingsRatioPct(), YearMonth.from(version.getCalcStartMonth()).toString(),
                plan.getBaselineScenario(), plan.getRevision(), plan.getPreviousVersionId() != null,
                version.getCreatedAt());
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }
}
