package com.moeun.backend.calc.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.moeun.backend.plan.ConditionOverride;

import tools.jackson.databind.JsonNode;

/**
 * 대화로 바꾼 조건(JSON)을 계산 입력으로 바꾸는 공통 코드(D-092·D-097, CP-07).
 * <p>{@code /internal/calc}(검증 경로)와 대화 기록 재계산(타입만 바꾸는 경로, #78)이 같이 쓴다. 여기서는 키를 고르고
 * 타입만 바꾸며, 무엇이 맞지 않았는지 알려 준다. 그것을 400·422로 낼지 예외로 낼지는 각 경로가 정한다.
 * 범위 검사는 하지 않는다(검증 경로는 plan의 {@code ConditionValidator}가 한다).
 */
public final class ConditionChanges {

    public static final String GOAL_AMOUNT = "goalAmount";
    public static final String MONTHLY_SAVING_AMOUNT = "monthlySavingAmount";
    public static final String TARGET_PERIOD_MONTHS = "targetPeriodMonths";
    public static final String SAVINGS_RATIO_PCT = "savingsRatioPct";
    public static final String SAVINGS_RATE_INPUT_PCT = "savingsRateInputPct";
    /** ETF 종목 코드(D-097, #157). 이 키만 값이 문자열이다. */
    public static final String ETF_PRODUCT_CODE = "etfProductCode";
    /** 대화로 바꿀 수 있는 조건 6개(D-092·D-097, CP-07). 이 밖의 키는 받지 않는다. */
    public static final Set<String> CHANGEABLE_KEYS = Set.of(GOAL_AMOUNT, MONTHLY_SAVING_AMOUNT,
            TARGET_PERIOD_MONTHS, SAVINGS_RATIO_PCT, SAVINGS_RATE_INPUT_PCT, ETF_PRODUCT_CODE);

    private ConditionChanges() {
    }

    /** 값이 null인 키("바꾸지 않음")를 뺀다. 요청에 적힌 순서를 지킨다. */
    public static Map<String, JsonNode> withoutNulls(JsonNode changes) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        changes.properties().forEach(entry -> {
            if (!entry.getValue().isNull()) {
                result.put(entry.getKey(), entry.getValue());
            }
        });
        return result;
    }

    /** 값이 null인 키를 뺀다. 요청에 적힌 순서를 지킨다. */
    public static Map<String, JsonNode> withoutNulls(Map<String, JsonNode> changes) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        changes.forEach((key, value) -> {
            if (value != null && !value.isNull()) {
                result.put(key, value);
            }
        });
        return result;
    }

    /** 허용 6개 밖의 키를 요청 순서로 돌려준다. */
    public static List<String> unchangeableKeys(Map<String, JsonNode> changes) {
        return changes.keySet().stream().filter(key -> !CHANGEABLE_KEYS.contains(key)).toList();
    }

    /**
     * 값의 JSON 타입이 모두 맞는지. 종목 코드는 문자열, 나머지는 유한한 숫자다. 문자열·불리언이나 1e400처럼 유한한 수로
     * 읽을 수 없는 값은 숫자가 아니다.
     */
    public static boolean allValuesWellTyped(Map<String, JsonNode> changes) {
        return changes.entrySet().stream().allMatch(entry -> ETF_PRODUCT_CODE.equals(entry.getKey())
                ? entry.getValue().isString() : isFiniteNumber(entry.getValue()));
    }

    /**
     * 허용 키의 값을 바꾼다. 부르는 쪽이 먼저 null을 빼고, 허용 밖 키와 타입이 맞지 않는 값을 걸러야 한다.
     * <ul>
     *     <li>정수 자리(목표 금액·월 저축액·기간·비중)에 소수가 있으면 잘라서 통과시키지 않고 null로 두고 그 키를 알린다</li>
     *     <li>타입 범위를 넘는 정수는 경계값으로 넘기고 그 키를 알린다. 검증 경로는 범위 검사가 OUT_OF_RANGE로 알린다</li>
     *     <li>금리는 소수 그대로 둔다(자릿수 검사는 범위 검사가 한다)</li>
     *     <li>종목 코드는 문자열 그대로 따로 돌려준다. 있는 종목인지는 보지 않는다</li>
     * </ul>
     */
    public static Conversion convert(Map<String, JsonNode> changes) {
        List<String> notInteger = new ArrayList<>();
        List<String> outOfTypeRange = new ArrayList<>();
        Long goalAmount = null;
        Long monthlySavingAmount = null;
        Integer targetPeriodMonths = null;
        Integer savingsRatioPct = null;
        BigDecimal savingsRateInputPct = null;
        String etfProductCode = null;
        for (Map.Entry<String, JsonNode> entry : changes.entrySet()) {
            String key = entry.getKey();
            if (ETF_PRODUCT_CODE.equals(key)) {
                etfProductCode = entry.getValue().asString();
                continue;
            }
            BigDecimal value = entry.getValue().decimalValue();
            switch (key) {
                case GOAL_AMOUNT -> goalAmount = toInteger(key, value, Long.MIN_VALUE, Long.MAX_VALUE,
                        notInteger, outOfTypeRange);
                case MONTHLY_SAVING_AMOUNT -> monthlySavingAmount = toInteger(key, value, Long.MIN_VALUE,
                        Long.MAX_VALUE, notInteger, outOfTypeRange);
                case TARGET_PERIOD_MONTHS -> targetPeriodMonths = toInt(key, value, notInteger, outOfTypeRange);
                case SAVINGS_RATIO_PCT -> savingsRatioPct = toInt(key, value, notInteger, outOfTypeRange);
                case SAVINGS_RATE_INPUT_PCT -> savingsRateInputPct = value;
                default -> throw new IllegalArgumentException("condition is not changeable: " + key);
            }
        }
        return new Conversion(new ConditionOverride(monthlySavingAmount, targetPeriodMonths, savingsRatioPct,
                savingsRateInputPct, goalAmount, null), etfProductCode, List.copyOf(notInteger),
                List.copyOf(outOfTypeRange));
    }

    private static Integer toInt(String key, BigDecimal value, List<String> notInteger, List<String> outOfTypeRange) {
        Long integer = toInteger(key, value, Integer.MIN_VALUE, Integer.MAX_VALUE, notInteger, outOfTypeRange);
        return integer == null ? null : integer.intValue();
    }

    private static Long toInteger(String key, BigDecimal value, long min, long max, List<String> notInteger,
            List<String> outOfTypeRange) {
        if (value.stripTrailingZeros().scale() > 0) {
            notInteger.add(key);
            return null;
        }
        if (value.compareTo(BigDecimal.valueOf(max)) > 0) {
            outOfTypeRange.add(key);
            return max;
        }
        if (value.compareTo(BigDecimal.valueOf(min)) < 0) {
            outOfTypeRange.add(key);
            return min;
        }
        return value.longValueExact();
    }

    private static boolean isFiniteNumber(JsonNode node) {
        return node.isNumber() && (!node.isFloatingPointNumber() || Double.isFinite(node.doubleValue()));
    }

    /**
     * 변환 결과.
     *
     * @param override 바꾼 조건. 없는 키·소수라서 버린 키는 null
     * @param etfProductCode 바꾼 ETF 종목 코드. 없으면 null
     * @param notIntegerKeys 정수 자리에 소수가 온 키(요청 순서)
     * @param outOfTypeRangeKeys 타입 범위를 넘어 경계값으로 넘긴 키(요청 순서)
     */
    public record Conversion(ConditionOverride override, String etfProductCode, List<String> notIntegerKeys,
            List<String> outOfTypeRangeKeys) {

        /** 버리거나 바꾼 값 없이 그대로 바뀌었는지. */
        public boolean isExact() {
            return notIntegerKeys.isEmpty() && outOfTypeRangeKeys.isEmpty();
        }
    }
}
