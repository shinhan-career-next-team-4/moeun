package com.moeun.backend.chat.web.compare;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.calc.CalcRejectedException;
import com.moeun.backend.calc.ConditionComparisonQuery;
import com.moeun.backend.config.copy.CopyBook;
import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.ConditionValidator;
import com.moeun.backend.plan.FieldError;

import tools.jackson.databind.JsonNode;

/**
 * 조건 변경 비교 {@code POST /api/compare}(FR-CMP-02, #64). 사용자가 조건을 바꿔 현재와 변경 결과를 나란히 본다.
 * <p>검증은 공용 COMPARE 규칙({@code rules.yml}, D-090·D-092)이 한다. 허용 키 밖·범위 밖·정수 자리의 소수·숫자가 아닌 값은
 * 걸린 필드를 모두 담아 422 VALIDATION_FAILED다. 계산은 calc의 공개 API가 하며 계획을 바꾸거나 비교 결과를 저장하지
 * 않는다. 회원은 JWT로만 정한다. 본문 형식 오류(객체가 아님, conditionChanges가 객체가 아님)는 400 MALFORMED_REQUEST다.
 */
@RestController
class ConditionCompareController {

    private static final String CONDITION_CHANGES = "conditionChanges";

    private final ConditionValidator validator;
    private final ConditionComparisonQuery comparisons;
    private final CopyBook copy;

    ConditionCompareController(ConditionValidator validator, ConditionComparisonQuery comparisons, CopyBook copy) {
        this.validator = validator;
        this.comparisons = comparisons;
        this.copy = copy;
    }

    @PostMapping("/api/compare")
    ConditionCompareResponse compare(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
        JsonNode changesNode = body.isObject() ? body.get(CONDITION_CHANGES) : null;
        if (changesNode == null || !changesNode.isObject()) {
            throw CompareRequestException.malformed();
        }
        Map<String, JsonNode> changes = new LinkedHashMap<>();
        changesNode.properties().forEach(entry -> changes.put(entry.getKey(), entry.getValue()));
        List<FieldError> errors = validator.validateChanges(ChangePath.COMPARE, changes);
        if (!errors.isEmpty()) {
            throw CompareRequestException.validationFailed(errors, copy.get("error.title.VALIDATION_FAILED"));
        }
        try {
            return ConditionCompareResponse.from(
                    comparisons.compareWithCurrentPlan(Long.parseLong(jwt.getSubject()), toOverride(changes)),
                    changedKeys(changes));
        } catch (CalcRejectedException rejected) {
            throw CompareRequestException.rejected(rejected.status(), rejected.code(),
                    copy.get("error.title." + rejected.code()));
        }
    }

    /** 값이 null이 아닌 키. 요청 순서를 지킨다. */
    private static List<String> changedKeys(Map<String, JsonNode> changes) {
        return changes.entrySet().stream().filter(entry -> !entry.getValue().isNull()).map(Map.Entry::getKey).toList();
    }

    /** 검증을 통과한 값이라 범위와 자릿수가 맞다. null인 키는 바꾸지 않음이다. */
    private static ConditionOverride toOverride(Map<String, JsonNode> changes) {
        return new ConditionOverride(longOf(changes, "monthlySavingAmount"), intOf(changes, "targetPeriodMonths"),
                intOf(changes, "savingsRatioPct"), decimalOf(changes, "savingsRateInputPct"),
                longOf(changes, "goalAmount"), null);
    }

    private static Long longOf(Map<String, JsonNode> changes, String key) {
        BigDecimal value = decimalOf(changes, key);
        return value == null ? null : value.longValueExact();
    }

    private static Integer intOf(Map<String, JsonNode> changes, String key) {
        BigDecimal value = decimalOf(changes, key);
        return value == null ? null : value.intValueExact();
    }

    private static BigDecimal decimalOf(Map<String, JsonNode> changes, String key) {
        JsonNode value = changes.get(key);
        return value == null || value.isNull() ? null : value.decimalValue();
    }
}
