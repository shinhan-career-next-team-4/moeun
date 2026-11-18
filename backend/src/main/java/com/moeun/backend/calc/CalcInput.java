package com.moeun.backend.calc;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/**
 * 계산 엔진에 실제로 넣는 조건. 기본금리·기본 비중·바꾼 조건을 모두 반영한 뒤의 값이다.
 * <p>금액은 원(KRW) 정수, 기간은 개월 정수다. null의 뜻은 필드마다 아래와 같이 하나로 정한다.
 * 입력 허용 범위(CP-02)는 plan 모듈의 검증이 맡고, 여기서는 계산이 성립하지 않는 값만 막는다.
 *
 * @param goalAmount 목표 금액. 0보다 커야 한다
 * @param targetPeriodMonths 계산 시작월부터 목표월까지 남은 개월 수. null = 기한 미정.
 *     해커톤 범위 밖(D-045)이라 엔진이 계산하지 않고 IllegalArgumentException으로 막는다
 * @param calcStartMonth 계산 시작월. 항상 계획에 저장된 calc_start_month다(처음 저장한 달, 이후 바꾸지 않음).
 *     대화·비교·대안으로 다시 계산해도 같은 값이다(D-013, D-032, 계약 C2-8)
 * @param heldAssets 보유 자산. 0 이상이며 운용 수익 없이 원금에 더한다
 * @param monthlySavingAmount 월 저축액. null = '잘 모르겠어요'.
 *     해커톤 범위 밖(D-045)이라 엔진이 계산하지 않고 IllegalArgumentException으로 막는다
 * @param savingsRatePct 적금 금리(연, 세전, %). 사용자가 비웠으면 기본금리가 채워져 들어온다
 * @param savingsRatioPct 월 저축액 중 적금 비중(0~100 정수). 나머지가 ETF 비중이다
 * @param baselineScenario 달성/미달 판정에 쓸 기준 시나리오
 */
public record CalcInput(
        long goalAmount,
        Integer targetPeriodMonths,
        YearMonth calcStartMonth,
        long heldAssets,
        Long monthlySavingAmount,
        BigDecimal savingsRatePct,
        int savingsRatioPct,
        Scenario baselineScenario) {

    private static final int MAX_RATIO_PCT = 100;

    public CalcInput {
        Objects.requireNonNull(calcStartMonth, "calcStartMonth");
        Objects.requireNonNull(savingsRatePct, "savingsRatePct");
        Objects.requireNonNull(baselineScenario, "baselineScenario");
        // 금융 값은 로그에 남기지 않기로 해서 메시지에는 항목 이름만 담는다
        requireThat(goalAmount > 0, "goalAmount must be positive");
        requireThat(targetPeriodMonths == null || targetPeriodMonths > 0, "targetPeriodMonths must be positive");
        requireThat(heldAssets >= 0, "heldAssets must not be negative");
        requireThat(monthlySavingAmount == null || monthlySavingAmount >= 0,
                "monthlySavingAmount must not be negative");
        requireThat(savingsRatePct.signum() >= 0, "savingsRatePct must not be negative");
        requireThat(savingsRatioPct >= 0 && savingsRatioPct <= MAX_RATIO_PCT,
                "savingsRatioPct must be between 0 and 100");
    }

    /** ETF 비중(0~100 정수). */
    public int etfRatioPct() {
        return MAX_RATIO_PCT - savingsRatioPct;
    }

    /** ETF가 포함되는지. 포함되지 않으면 3구간이 갈리지 않는다(FR-RES-03). */
    public boolean hasEtf() {
        return savingsRatioPct < MAX_RATIO_PCT;
    }

    /** 보유 자산이 이미 목표 금액 이상인지(FR-RES-01, CF-03). */
    public boolean hasExceededGoal() {
        return heldAssets >= goalAmount;
    }

    /** 목표월. 기한 미정이면 비어 있다. */
    public Optional<YearMonth> findTargetMonth() {
        return Optional.ofNullable(targetPeriodMonths).map(calcStartMonth::plusMonths);
    }

    private static void requireThat(boolean isValid, String message) {
        if (!isValid) {
            throw new IllegalArgumentException(message);
        }
    }
}
