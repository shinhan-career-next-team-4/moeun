package com.moeun.backend.calc;

import java.math.BigDecimal;

/**
 * planContext의 {@code conditions}(계약 C1a-3). 계산에 실제로 쓴 조건 6개와 계획의 ETF 종목 코드이고 키는 늘 모두 있다.
 * <p>출처는 {@link CalcInput} 하나다(기본금리·기본 비중을 해석한 뒤의 값). {@code plan_version}의 금리 컬럼은 NULL일 수
 * 있어 그대로 읽으면 틀린다. {@code goalType}·{@code goalCustomName}은 넣지 않는다(C1a-7).
 *
 * @param goalAmount 목표 금액(원)
 * @param heldAssets 보유 자산(원)
 * @param monthlySavingAmount 월 저축액(원). 해커톤에서는 늘 값이 있지만 타입은 nullable 그대로다
 * @param targetPeriodMonths 목표 기간(개월). 위와 같다
 * @param savingsRatioPct 해석된 적금 비중(%)
 * @param savingsRateInputPct 실제 쓴 적금 금리(연, 세전, %). 기본금리를 썼어도 그 값이다
 * @param etfProductCode 계획에 저장된 ETF 종목 코드(#297). ETF 0%여도 있다. 계산에 쓴 종목은
 *        {@code result.assumptions.etfProductCode}(ETF 0%면 null)다
 */
public record ConditionsView(long goalAmount, long heldAssets, Long monthlySavingAmount, Integer targetPeriodMonths,
        int savingsRatioPct, BigDecimal savingsRateInputPct, String etfProductCode) {

    public static ConditionsView from(CalcInput input, String etfProductCode) {
        return new ConditionsView(input.goalAmount(), input.heldAssets(), input.monthlySavingAmount(),
                input.targetPeriodMonths(), input.savingsRatioPct(), input.savingsRatePct(), etfProductCode);
    }
}
