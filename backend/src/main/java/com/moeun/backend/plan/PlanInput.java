package com.moeun.backend.plan;

import java.math.BigDecimal;

/**
 * 입력 화면 값 전체(계약 C12a-5-1). 요청 JSON 해석은 web에서 끝나고 숫자는 받은 그대로 {@link BigDecimal}로 온다.
 * {@code 300000.5}가 정수로 잘려 통과하지 않도록 저장 서비스가 소수 자리·범위를 검증한 뒤 정수로 바꾼다.
 * 금액은 원, 금리는 %, 비중은 적금 %. null은 값 없음이다.
 */
public record PlanInput(
        GoalType goalType,
        String goalCustomName,
        BigDecimal goalAmount,
        BigDecimal targetPeriodMonths,
        BigDecimal heldAssets,
        BigDecimal monthlySavingAmount,
        BigDecimal savingsRateInputPct,
        BigDecimal savingsRatioPct) {
}
