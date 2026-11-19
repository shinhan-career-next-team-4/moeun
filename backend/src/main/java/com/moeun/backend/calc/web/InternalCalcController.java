package com.moeun.backend.calc.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.calc.CalcRejectedException;
import com.moeun.backend.calc.application.CompareConditionsUseCase;
import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.ConditionValidator;
import com.moeun.backend.plan.FieldError;

import tools.jackson.databind.JsonNode;

/**
 * ai-server 전용 대화 재계산 {@code POST /internal/calc}(계약 internal-calc C2). 내 계획 REST와 다른 클래스다.
 * <p>인증은 {@code /internal/**} 보안 체인의 공유 비밀 헤더가 한다(검사 순서 0). 본문은 {@link JsonNode}로 받아 키 없음·null·
 * 소수를 구분한다. Boot 기본값으로는 {@code goalAmount}가 조용히 버려지고 {@code 500000.5}·{@code memberId: 7.5}가 잘린다.
 * 요청 본문의 memberId를 믿는 유일한 경로다(ai-server만 부른다).
 */
@RestController
class InternalCalcController {

    private static final String MEMBER_ID = "memberId";
    private static final String CONDITION_CHANGES = "conditionChanges";
    private static final String MONTHLY_SAVING_AMOUNT = "monthlySavingAmount";
    private static final String SAVINGS_RATIO_PCT = "savingsRatioPct";
    private static final String SAVINGS_RATE_INPUT_PCT = "savingsRateInputPct";
    private static final String TARGET_PERIOD_MONTHS = "targetPeriodMonths";
    /** 대화로 바꿀 수 있는 조건(D-040, CP-07). 이 밖의 키가 하나라도 있으면 요청 전체를 거절한다. */
    private static final Set<String> CHANGEABLE_KEYS = Set.of(MONTHLY_SAVING_AMOUNT, SAVINGS_RATIO_PCT,
            SAVINGS_RATE_INPUT_PCT, TARGET_PERIOD_MONTHS);

    private final ConditionValidator conditionValidator;
    private final CompareConditionsUseCase compareConditionsUseCase;

    InternalCalcController(ConditionValidator conditionValidator, CompareConditionsUseCase compareConditionsUseCase) {
        this.conditionValidator = conditionValidator;
        this.compareConditionsUseCase = compareConditionsUseCase;
    }

    @PostMapping("/internal/calc")
    InternalCalcResponse calculate(@RequestBody JsonNode body) {
        // 1. 형식: memberId는 정수, conditionChanges는 객체. baselineScenario는 받기만 하고 보지 않는다
        if (!body.isObject() || !isLong(body.get(MEMBER_ID)) || !isObject(body.get(CONDITION_CHANGES))) {
            throw new MalformedInternalCalcRequestException();
        }
        long memberId = body.get(MEMBER_ID).longValue();
        // 2. 값이 null인 키는 "바꾸지 않음"이라 먼저 지운다
        Map<String, JsonNode> changes = new LinkedHashMap<>();
        body.get(CONDITION_CHANGES).properties().forEach(entry -> {
            if (!entry.getValue().isNull()) {
                changes.put(entry.getKey(), entry.getValue());
            }
        });
        // 3. 허용 밖 키가 하나라도 있으면 일부만 계산하지 않고 전체를 거절한다(사용자가 말한 것과 다른 비교를 막음)
        List<FieldError> notChangeable = changes.keySet().stream()
                .filter(key -> !CHANGEABLE_KEYS.contains(key))
                .map(FieldError::notChangeable)
                .toList();
        if (!notChangeable.isEmpty()) {
            throw CalcRejectedException.fieldNotChangeable(notChangeable);
        }
        // 4. 허용 키의 값은 JSON 숫자여야 한다(1e400처럼 유한한 수로 읽을 수 없는 값도 형식 오류)
        if (changes.values().stream().anyMatch(value -> !isFiniteNumber(value))) {
            throw new MalformedInternalCalcRequestException();
        }
        // 5. 바꿀 값이 하나도 없으면
        if (changes.isEmpty()) {
            throw CalcRejectedException.validationFailed(List.of(FieldError.required(CONDITION_CHANGES)));
        }
        // 6. 범위·정수·소수 자릿수. 걸린 키를 모두, 요청에 적힌 순서로 담는다
        ConditionOverride override = validate(changes);
        CompareConditionsUseCase.ComparisonResult result = compareConditionsUseCase.compare(memberId, override);
        return InternalCalcResponse.from(result);
    }

    private ConditionOverride validate(Map<String, JsonNode> changes) {
        List<FieldError> errors = new ArrayList<>();
        Long monthlySavingAmount = toInteger(changes, MONTHLY_SAVING_AMOUNT, Long.MIN_VALUE, Long.MAX_VALUE, errors);
        Long savingsRatioPct = toInteger(changes, SAVINGS_RATIO_PCT, Integer.MIN_VALUE, Integer.MAX_VALUE, errors);
        Long targetPeriodMonths = toInteger(changes, TARGET_PERIOD_MONTHS, Integer.MIN_VALUE, Integer.MAX_VALUE,
                errors);
        JsonNode rate = changes.get(SAVINGS_RATE_INPUT_PCT);
        ConditionOverride override = new ConditionOverride(monthlySavingAmount,
                targetPeriodMonths == null ? null : targetPeriodMonths.intValue(),
                savingsRatioPct == null ? null : savingsRatioPct.intValue(),
                rate == null ? null : rate.decimalValue());
        boolean hasValidatableValue = monthlySavingAmount != null || savingsRatioPct != null
                || targetPeriodMonths != null || rate != null;
        if (hasValidatableValue) {
            errors.addAll(conditionValidator.validate(ChangePath.CHAT, override));
        }
        if (!errors.isEmpty()) {
            List<String> requestOrder = new ArrayList<>(changes.keySet());
            errors.sort(Comparator.comparingInt(error -> requestOrder.indexOf(error.field())));
            throw CalcRejectedException.validationFailed(errors);
        }
        return override;
    }

    /**
     * 정수 자리의 값을 꺼낸다. 소수가 있으면 NOT_INTEGER로 기록하고 null을 돌려준다(잘라서 통과시키지 않음).
     * 표현 범위를 넘는 값은 경계값으로 넘겨 범위 검사가 OUT_OF_RANGE로 알리게 한다.
     */
    private static Long toInteger(Map<String, JsonNode> changes, String key, long min, long max,
            List<FieldError> errors) {
        JsonNode node = changes.get(key);
        if (node == null) {
            return null;
        }
        BigDecimal value = node.decimalValue();
        if (value.stripTrailingZeros().scale() > 0) {
            errors.add(FieldError.notInteger(key));
            return null;
        }
        if (value.compareTo(BigDecimal.valueOf(max)) > 0) {
            return max;
        }
        if (value.compareTo(BigDecimal.valueOf(min)) < 0) {
            return min;
        }
        return value.longValueExact();
    }

    private static boolean isFiniteNumber(JsonNode node) {
        return node.isNumber() && (!node.isFloatingPointNumber() || Double.isFinite(node.doubleValue()));
    }

    private static boolean isLong(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong();
    }

    private static boolean isObject(JsonNode node) {
        return node != null && node.isObject();
    }
}
