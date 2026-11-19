package com.moeun.backend.plan.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.ConditionValidator;
import com.moeun.backend.plan.ConditionViolationException;
import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.GoalType;
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
import com.moeun.backend.plan.domain.PlanRepository;
import com.moeun.backend.plan.domain.PlanVersion;
import com.moeun.backend.plan.domain.PlanVersionRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** 내 계획 저장(FR-INP-10), 적용(FR-PLAN-01~03), 되돌리기(FR-PLAN-04). 모든 조회·갱신은 memberId 조건을 붙인다. */
@Service
@Transactional
public class PlanService implements PlanSaveService, PlanApplyService, PlanUndoService {

    private static final String DEFAULT_BASELINE = "AVG";   // TBD-40: 기본값 평균

    /** 적용할 수 있는 경로. 입력 수정(INPUT_EDIT)은 저장 경로가 따로 있다(계약 C12b-13). */
    private static final Set<ChangePath> APPLY_PATHS = Set.of(ChangePath.ALTERNATIVE, ChangePath.COMPARE,
            ChangePath.CHAT);

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
        return planRepository.findById(memberId).map(this::toView);
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
        Optional<Plan> existing = planRepository.findById(memberId);
        checkExpectedRevision(existing, expectedRevision);

        PlanVersion currentVersion = existing.map(plan -> version(memberId, plan.getCurrentVersionId())).orElse(null);
        LocalDate calcStartMonth = currentVersion != null
                ? currentVersion.getCalcStartMonth()
                : YearMonth.now(clock).atDay(1);   // TBD-38: 처음 저장한 달로 고정
        Integer savingsRatioPct = currentVersion != null
                ? currentVersion.getSavingsRatioPct()
                : null;   // 요청에 없는 값은 현재 버전에서 복사(C12a-5-1)

        PlanVersion saved = versionRepository.save(newVersion(memberId, in, savingsRatioPct, calcStartMonth, "INPUT"));
        Long before = existing.map(Plan::getCurrentVersionId).orElse(null);
        long newRevision;
        if (existing.isEmpty()) {
            if (planRepository.insertIfAbsent(memberId, saved.getId(), DEFAULT_BASELINE) == 0) {
                throw new PlanChangedException();
            }
            newRevision = 1L;
        } else {
            if (planRepository.replaceCurrent(memberId, saved.getId(), expectedRevision) == 0) {
                throw new PlanChangedException();
            }
            newRevision = expectedRevision + 1;
        }
        events.publishEvent(new PlanChanged(memberId, "INPUT", before, saved.getId(), newRevision));
        return new PlanSaveResult(saved.getId(), newRevision);
    }

    /** 검사 순서는 계약 C12b-7-1을 따른다: 5 계획 없음 → 6·7 조건 검증 → 8 revision → 9 기준 버전 → 10 저장. */
    @Override
    public PlanApplyResult apply(long memberId, ChangePath path, Map<String, JsonNode> conditionChanges,
            long expectedRevision, Long expectedBaseVersionId) {
        if (!APPLY_PATHS.contains(path)) {
            throw new IllegalArgumentException("적용할 수 없는 경로입니다: " + path);
        }
        Plan plan = planRepository.findById(memberId).orElseThrow(NoPlanException::new);
        throwIfInvalid(conditionValidator.validateChanges(path, conditionChanges));
        // 8과 9가 둘 다 해당하면 큰 쪽인 PLAN_CHANGED를 알린다
        if (!plan.getRevision().equals(expectedRevision)) {
            throw new PlanChangedException();
        }
        // 잠금 없이 읽어도 된다. 아래 조건부 UPDATE가 성공했다면 그사이 포인터도 바뀌지 않았다
        if (expectedBaseVersionId != null && !expectedBaseVersionId.equals(plan.getCurrentVersionId())) {
            throw new StaleBaseVersionException();
        }
        PlanVersion current = version(memberId, plan.getCurrentVersionId());
        ConditionOverride changes = toOverride(conditionChanges);
        PlanInput merged = merge(current, changes);
        throwIfInvalid(inputValidator.validate(merged));
        Integer ratio = changes.savingsRatioPct() != null ? changes.savingsRatioPct() : current.getSavingsRatioPct();
        PlanVersion saved = versionRepository.save(
                newVersion(memberId, merged, ratio, current.getCalcStartMonth(), path.name()));
        if (planRepository.replaceCurrent(memberId, saved.getId(), expectedRevision) == 0) {
            throw new PlanChangedException();   // 롤백해 방금 INSERT한 plan_version을 남기지 않는다
        }
        long newRevision = expectedRevision + 1;
        events.publishEvent(new PlanChanged(memberId, "APPLY", current.getId(), saved.getId(), newRevision));
        return new PlanApplyResult(saved.getId(), current.getId(), newRevision);
    }

    /** 검증을 끝낸 변경값을 조건 레코드로 옮긴다. 키는 API와 같은 camelCase다. */
    private static ConditionOverride toOverride(Map<String, JsonNode> changes) {
        Integer ratio = intField(changes, InputField.SAVINGS_RATIO_PCT);
        return new ConditionOverride(
                longField(changes, InputField.MONTHLY_SAVING_AMOUNT),
                intField(changes, InputField.TARGET_PERIOD_MONTHS),
                ratio,
                decimalField(changes, InputField.SAVINGS_RATE_INPUT_PCT));
    }

    @Override
    public long undo(long memberId, long expectedRevision) {
        Plan plan = planRepository.findById(memberId).orElseThrow(NoPlanException::new);
        if (plan.getPreviousVersionId() == null) {
            throw new NothingToUndoException();
        }
        if (!plan.getRevision().equals(expectedRevision)) {
            throw new PlanChangedException();
        }
        PlanVersion previous = version(memberId, plan.getPreviousVersionId());
        PlanVersion copied = versionRepository.save(copyVersion(previous));
        if (planRepository.applyUndo(memberId, copied.getId(), expectedRevision) == 0) {
            throw new PlanChangedException();
        }
        long newRevision = expectedRevision + 1;
        events.publishEvent(new PlanChanged(memberId, "UNDO", plan.getCurrentVersionId(), copied.getId(), newRevision));
        return newRevision;
    }

    private void checkExpectedRevision(Optional<Plan> existing, Long expectedRevision) {
        if (existing.isEmpty()) {
            if (expectedRevision != null) {
                throw new PlanChangedException();
            }
            return;
        }
        Plan plan = existing.get();
        if (expectedRevision == null || !plan.getRevision().equals(expectedRevision)) {
            throw new PlanChangedException();
        }
    }

    private static void throwIfInvalid(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ConditionViolationException(errors);
        }
    }

    private PlanVersion version(long memberId, long versionId) {
        return versionRepository.findByIdAndMemberId(versionId, memberId).orElseThrow();
    }

    /** 현재 버전에 바뀐 값만 덮는다. */
    private static PlanInput merge(PlanVersion current, ConditionOverride changes) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        JsonNodeFactory factory = JsonNodeFactory.instance;
        fields.put(InputField.GOAL_AMOUNT.apiName(), factory.numberNode(current.getGoalAmount()));
        fields.put(InputField.HELD_ASSETS.apiName(), factory.numberNode(current.getHeldAssets()));
        fields.put(InputField.TARGET_PERIOD_MONTHS.apiName(),
                factory.numberNode(firstNonNull(changes.targetPeriodMonths(), current.getTargetPeriodMonths())));
        fields.put(InputField.MONTHLY_SAVING_AMOUNT.apiName(),
                factory.numberNode(firstNonNull(changes.monthlySavingAmount(), current.getMonthlySaving())));
        BigDecimal rate = firstNonNull(changes.savingsRateInputPct(), current.getSavingsRatePct());
        if (rate != null) {
            fields.put(InputField.SAVINGS_RATE_INPUT_PCT.apiName(), factory.numberNode(rate));
        }
        return new PlanInput(current.getGoalType(), current.getGoalCustomName(), fields);
    }

    private PlanVersion newVersion(long memberId, PlanInput in, Integer savingsRatioPct, LocalDate calcStartMonth,
            String source) {
        String customName = in.goalCustomName() == null ? null : in.goalCustomName().strip();
        Map<String, JsonNode> fields = in.fields();
        return new PlanVersion(memberId, in.goalType(),
                in.goalType() == GoalType.CUSTOM ? customName : null,
                longField(fields, InputField.GOAL_AMOUNT),
                intField(fields, InputField.TARGET_PERIOD_MONTHS),
                calcStartMonth,
                longField(fields, InputField.HELD_ASSETS),
                longField(fields, InputField.MONTHLY_SAVING_AMOUNT),
                decimalField(fields, InputField.SAVINGS_RATE_INPUT_PCT),
                savingsRatioPct, source, clock.instant());
    }

    private PlanVersion copyVersion(PlanVersion source) {
        return new PlanVersion(
                source.getMemberId(),
                source.getGoalType(),
                source.getGoalCustomName(),
                source.getGoalAmount(),
                source.getTargetPeriodMonths(),
                source.getCalcStartMonth(),
                source.getHeldAssets(),
                source.getMonthlySaving(),
                source.getSavingsRatePct(),
                source.getSavingsRatioPct(),
                source.getSource(),
                clock.instant());
    }

    private static Long longField(Map<String, JsonNode> fields, InputField field) {
        JsonNode node = fields.get(field.apiName());
        return node == null || node.isNull() ? null : node.longValue();
    }

    private static Integer intField(Map<String, JsonNode> fields, InputField field) {
        JsonNode node = fields.get(field.apiName());
        return node == null || node.isNull() ? null : node.intValue();
    }

    private static BigDecimal decimalField(Map<String, JsonNode> fields, InputField field) {
        JsonNode node = fields.get(field.apiName());
        return node == null || node.isNull() ? null : node.decimalValue();
    }

    private PlanView toView(Plan plan) {
        return toView(plan, version(plan.getMemberId(), plan.getCurrentVersionId()));
    }

    private static PlanView toView(Plan plan, PlanVersion version) {
        return new PlanView(
                version.getId(),
                version.getGoalType(),
                version.getGoalCustomName(),
                version.getGoalAmount(),
                version.getTargetPeriodMonths(),
                version.getHeldAssets(),
                version.getMonthlySaving(),
                version.getSavingsRatePct(),
                version.getSavingsRatioPct(),
                YearMonth.from(version.getCalcStartMonth()).toString(),
                plan.getBaselineScenario(),
                plan.getRevision(),
                plan.getPreviousVersionId() != null,
                version.getCreatedAt());
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }
}
