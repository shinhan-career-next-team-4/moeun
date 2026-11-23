package com.moeun.backend.calc.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.calc.CurrentPlanResult;
import com.moeun.backend.calc.CurrentPlanResultService;
import com.moeun.backend.calc.application.Formatter;
import com.moeun.backend.calc.application.SavePlanUseCase;
import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.NoPlanException;
import com.moeun.backend.plan.PlanInput;

import tools.jackson.databind.JsonNode;

/**
 * 내 계획 조회·입력 저장 REST(P-4 {@code GET}, P-3 {@code PUT}, 계약 C12a-5·C12a-6). {@code /internal/calc} 컨트롤러와
 * 다른 클래스다. 두 엔드포인트가 같은 화면 전용 PlanView를 돌려준다.
 * <p>본문을 {@link JsonNode}로 받는다. 키 없음과 null을 구분하고, {@code 300000.5}가 정수로 잘려 통과하지 않게 하려는 것이다.
 * 여기서는 형식(400)과 null 허용 키의 존재(422 REQUIRED)만 보고, 값 범위 검증은 plan 저장 서비스가 한다.
 */
@RestController
@RequestMapping("/api/plan")
class PlanResultController {

    private static final String EXPECTED_REVISION = "expectedRevision";
    private static final String GOAL_TYPE = "goalType";
    private static final String GOAL_CUSTOM_NAME = "goalCustomName";
    private static final String SAVINGS_RATE_INPUT_PCT = "savingsRateInputPct";
    private static final String SAVINGS_RATIO_PCT = "savingsRatioPct";
    /** null이어도 되지만 키는 꼭 있어야 하는 항목(C12a-5-1). */
    private static final List<String> NULLABLE_REQUIRED_KEYS = List.of(EXPECTED_REVISION, SAVINGS_RATE_INPUT_PCT,
            SAVINGS_RATIO_PCT);
    /** 값이 있으면 JSON 숫자여야 하는 항목. plan 저장 서비스는 이 키 이름으로 값을 읽는다. */
    private static final List<String> NUMBER_FIELDS = List.of("goalAmount", "targetPeriodMonths", "heldAssets",
            "monthlySavingAmount", SAVINGS_RATE_INPUT_PCT, SAVINGS_RATIO_PCT);

    private final SavePlanUseCase savePlanUseCase;
    private final CurrentPlanResultService resultService;
    private final Formatter formatter;

    PlanResultController(SavePlanUseCase savePlanUseCase, CurrentPlanResultService resultService,
            Formatter formatter) {
        this.savePlanUseCase = savePlanUseCase;
        this.resultService = resultService;
        this.formatter = formatter;
    }

    /**
     * 현재 계획과 결과(P-4). 결과 조회 서비스가 계획을 한 번만 읽고 그 버전의 calc_run(PLAN)을 쓰거나 없으면 계산·기록한다.
     * 응답은 그 스냅샷 하나로 만들고 계획을 따로 다시 읽지 않는다(C12a-6-3). 서비스의 트랜잭션은 읽기 전용이 아니다.
     * 재로그인해도 기준 데이터가 그대로면 같은 기록(같은 calcRunId)을 쓴다(D-010, C12a-6-6).
     */
    @GetMapping
    PlanResultResponse findCurrentPlan(@AuthenticationPrincipal Jwt jwt) {
        return resultService.findForCurrentPlan(memberId(jwt))
                .map(current -> PlanResultResponse.from(current, formatter))
                .orElseThrow(NoPlanException::new);
    }

    @PutMapping
    PlanResultResponse save(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
        checkFormat(body);
        checkNullableKeysPresent(body);
        JsonNode revision = body.get(EXPECTED_REVISION);
        Long expectedRevision = revision.isNull() ? null : revision.longValue();
        CurrentPlanResult saved = savePlanUseCase.save(memberId(jwt), toPlanInput(body), expectedRevision);
        return PlanResultResponse.from(saved, formatter);
    }

    /** 순서 1: JSON 숫자·알려진 목표 종류·정수 revision인지(C12a-5-2). 어기면 400 MALFORMED_REQUEST. */
    private static void checkFormat(JsonNode body) {
        if (!body.isObject()) {
            throw new MalformedPlanRequestException();
        }
        JsonNode revision = body.get(EXPECTED_REVISION);
        if (revision != null && !revision.isNull() && !(revision.isIntegralNumber() && revision.canConvertToLong())) {
            throw new MalformedPlanRequestException();
        }
        for (String field : NUMBER_FIELDS) {
            JsonNode value = body.get(field);
            if (value != null && !value.isNull() && !value.isNumber()) {
                throw new MalformedPlanRequestException();
            }
        }
        JsonNode goalType = body.get(GOAL_TYPE);
        if (goalType != null && !goalType.isNull() && (!goalType.isString() || !isKnownGoalType(goalType.asString()))) {
            throw new MalformedPlanRequestException();
        }
        JsonNode customName = body.get(GOAL_CUSTOM_NAME);
        if (customName != null && !customName.isNull() && !customName.isString()) {
            throw new MalformedPlanRequestException();
        }
    }

    /** null 허용 키가 아예 없으면 422 REQUIRED. 프론트는 늘 두 키를 보내므로 다른 필드 오류와 묶지 않는다. */
    private static void checkNullableKeysPresent(JsonNode body) {
        List<FieldError> missing = new ArrayList<>();
        for (String key : NULLABLE_REQUIRED_KEYS) {
            if (!body.has(key)) {
                missing.add(FieldError.required(key));
            }
        }
        if (!missing.isEmpty()) {
            throw new MissingPlanKeysException(missing);
        }
    }

    private static PlanInput toPlanInput(JsonNode body) {
        JsonNode goalType = body.get(GOAL_TYPE);
        JsonNode customName = body.get(GOAL_CUSTOM_NAME);
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        for (String field : NUMBER_FIELDS) {
            if (body.has(field)) {
                fields.put(field, body.get(field));
            }
        }
        return new PlanInput(
                goalType == null || goalType.isNull() ? null : GoalType.valueOf(goalType.asString()),
                customName == null || customName.isNull() ? null : customName.asString(),
                fields);
    }

    private static boolean isKnownGoalType(String code) {
        return Arrays.stream(GoalType.values()).anyMatch(type -> type.name().equals(code));
    }

    private static long memberId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
