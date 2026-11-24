package com.moeun.backend.plan.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.ConditionValidator;
import com.moeun.backend.plan.ConditionViolationException;
import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.InputField;
import com.moeun.backend.plan.NoPlanException;
import com.moeun.backend.plan.NothingToUndoException;
import com.moeun.backend.plan.PlanApplyResult;
import com.moeun.backend.plan.PlanApplyService;
import com.moeun.backend.plan.PlanChanged;
import com.moeun.backend.plan.PlanChangedException;
import com.moeun.backend.plan.PlanInput;
import com.moeun.backend.plan.PlanSaveResult;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.plan.PlanView;
import com.moeun.backend.plan.StaleBaseVersionException;
import com.moeun.backend.plan.domain.Plan;
import com.moeun.backend.plan.domain.PlanConditions;
import com.moeun.backend.plan.domain.PlanRepository;
import com.moeun.backend.plan.domain.PlanSource;
import com.moeun.backend.plan.domain.PlanVersion;
import com.moeun.backend.plan.domain.PlanVersionRepository;

import tools.jackson.databind.JsonNode;

/**
 * 내 계획 저장(FR-INP-10), 적용(FR-PLAN-01~03), 되돌리기(FR-PLAN-04). 모든 조회·갱신은 memberId 조건을 붙인다.
 * 새 버전을 만드는 규칙은 PlanVersion, 포인터 규칙은 Plan이 가진다. 이 클래스는 읽기 → 검증 → 저장 → 발행 순서만 적는다.
 */
@Service
@Transactional
public class PlanService implements PlanSaveService, PlanApplyService, PlanUndoService {

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

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanView> findCurrent(long memberId) {
        return planRepository.findById(memberId).map(plan -> toView(plan, version(memberId,
                plan.getCurrentVersionId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanView> findVersion(long memberId, long planVersionId) {
        return versionRepository.findByIdAndMemberId(planVersionId, memberId)
                .flatMap(version -> planRepository.findById(memberId).map(plan -> toView(plan, version)));
    }

    @Override
    public PlanSaveResult save(long memberId, PlanInput in, Long expectedRevision) {
        throwIfInvalid(inputValidator.validate(in));
        PlanConditions conditions = PlanConditions.from(in);
        Optional<Plan> existing = planRepository.findById(memberId);
        if (existing.isEmpty()) {
            return saveFirst(memberId, conditions, expectedRevision);
        }
        Plan plan = existing.get();
        plan.requireRevision(expectedRevision);
        PlanVersion current = version(memberId, plan.getCurrentVersionId());
        PlanVersion saved = versionRepository.save(current.revise(conditions, clock.instant()));
        plan.pointTo(saved.getId());
        flushOrPlanChanged();
        events.publishEvent(new PlanChanged(memberId, "INPUT", current.getId(), saved.getId(), plan.getRevision()));
        return new PlanSaveResult(saved.getId(), plan.getRevision());
    }

    private PlanSaveResult saveFirst(long memberId, PlanConditions conditions, Long expectedRevision) {
        if (expectedRevision != null) {
            throw new PlanChangedException();   // 계획이 있다고 믿는 화면에서 왔다
        }
        PlanVersion saved = versionRepository.save(
                PlanVersion.first(memberId, conditions, YearMonth.now(clock), clock.instant()));
        Plan plan = Plan.start(memberId, saved.getId());
        if (!planRepository.insertIfAbsent(plan)) {
            throw new PlanChangedException();   // 동시에 첫 저장한 요청이 먼저 넣었다
        }
        events.publishEvent(new PlanChanged(memberId, "INPUT", null, saved.getId(), plan.getRevision()));
        return new PlanSaveResult(saved.getId(), plan.getRevision());
    }

    /** 검사 순서는 계약 C12b-7-1을 따른다: 5 계획 없음 → 6·7 조건 검증 → 8 revision → 9 기준 버전 → 10 저장. */
    @Override
    public PlanApplyResult apply(long memberId, ChangePath path, Map<String, JsonNode> conditionChanges,
            long expectedRevision, Long expectedBaseVersionId) {
        PlanSource source = PlanSource.appliedBy(path);
        Plan plan = planRepository.findById(memberId).orElseThrow(NoPlanException::new);
        throwIfInvalid(conditionValidator.validateChanges(path, conditionChanges));
        // 8과 9가 둘 다 해당하면 큰 쪽인 PLAN_CHANGED를 알린다
        plan.requireRevision(expectedRevision);
        // 잠금 없이 읽어도 된다. 저장 시점의 @Version 검사를 통과했다면 그사이 포인터도 바뀌지 않았다
        if (expectedBaseVersionId != null && !expectedBaseVersionId.equals(plan.getCurrentVersionId())) {
            throw new StaleBaseVersionException();
        }
        PlanVersion current = version(memberId, plan.getCurrentVersionId());
        PlanVersion next = current.applyOverride(toOverride(conditionChanges),
                textField(conditionChanges, InputField.ETF_PRODUCT_CODE), source, clock.instant());
        throwIfInvalid(inputValidator.validate(next.getConditions().toInput()));
        PlanVersion saved = versionRepository.save(next);
        plan.pointTo(saved.getId());
        flushOrPlanChanged();   // 실패하면 롤백해 방금 INSERT한 plan_version을 남기지 않는다
        events.publishEvent(new PlanChanged(memberId, "APPLY", current.getId(), saved.getId(), plan.getRevision()));
        return new PlanApplyResult(saved.getId(), current.getId(), plan.getRevision());
    }

    @Override
    public long undo(long memberId, long expectedRevision) {
        Plan plan = planRepository.findById(memberId).orElseThrow(NoPlanException::new);
        long before = plan.getCurrentVersionId();
        if (!plan.canUndo()) {
            throw new NothingToUndoException();   // revision 검사보다 먼저다
        }
        plan.requireRevision(expectedRevision);
        PlanVersion previous = version(memberId, plan.getPreviousVersionId());
        PlanVersion copied = versionRepository.save(previous.copyForUndo(clock.instant()));
        plan.undoTo(copied.getId());
        flushOrPlanChanged();
        events.publishEvent(new PlanChanged(memberId, "UNDO", before, copied.getId(), plan.getRevision()));
        return plan.getRevision();
    }

    /** 포인터 변경을 지금 써서 @Version 충돌을 409 PLAN_CHANGED로 바꾼다. 커밋 때 터지면 응답 코드를 고를 수 없다. */
    private void flushOrPlanChanged() {
        try {
            planRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            throw new PlanChangedException();
        }
    }

    /** 검증을 끝낸 변경값을 조건 레코드로 옮긴다. 키는 API와 같은 camelCase다. */
    private static ConditionOverride toOverride(Map<String, JsonNode> changes) {
        return new ConditionOverride(
                longField(changes, InputField.MONTHLY_SAVING_AMOUNT),
                intField(changes, InputField.TARGET_PERIOD_MONTHS),
                intField(changes, InputField.SAVINGS_RATIO_PCT),
                decimalField(changes, InputField.SAVINGS_RATE_INPUT_PCT),
                longField(changes, InputField.GOAL_AMOUNT),
                longField(changes, InputField.HELD_ASSETS));
    }

    private static void throwIfInvalid(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ConditionViolationException(errors);
        }
    }

    private PlanVersion version(long memberId, long versionId) {
        return versionRepository.findByIdAndMemberId(versionId, memberId).orElseThrow();
    }

    private static Long longField(Map<String, JsonNode> fields, InputField field) {
        JsonNode node = fields.get(field.apiName());
        return node == null || node.isNull() ? null : node.longValue();
    }

    private static String textField(Map<String, JsonNode> fields, InputField field) {
        JsonNode node = fields.get(field.apiName());
        return node == null || node.isNull() ? null : node.asString();
    }

    private static Integer intField(Map<String, JsonNode> fields, InputField field) {
        JsonNode node = fields.get(field.apiName());
        return node == null || node.isNull() ? null : node.intValue();
    }

    private static BigDecimal decimalField(Map<String, JsonNode> fields, InputField field) {
        JsonNode node = fields.get(field.apiName());
        return node == null || node.isNull() ? null : node.decimalValue();
    }

    private static PlanView toView(Plan plan, PlanVersion version) {
        PlanConditions conditions = version.getConditions();
        return new PlanView(
                version.getId(),
                conditions.goalType(),
                conditions.goalCustomName(),
                conditions.goalAmount(),
                conditions.targetPeriodMonths(),
                conditions.heldAssets(),
                conditions.monthlySaving(),
                conditions.savingsRatePct(),
                conditions.savingsRatioPct(),
                version.getEtfProductCode(),
                YearMonth.from(version.getCalcStartMonth()).toString(),
                plan.getBaselineScenario().name(),
                plan.getRevision(),
                plan.canUndo(),
                version.getCreatedAt());
    }
}
