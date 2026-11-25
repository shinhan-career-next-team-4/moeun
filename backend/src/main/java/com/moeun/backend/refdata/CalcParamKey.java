package com.moeun.backend.refdata;

import java.math.BigDecimal;

/**
 * 계산 파라미터 항목. 값은 calc_param 테이블에 버전과 함께 둔다.
 * 코드 상수로 두면 값을 바꿀 때 과거 계산을 재현할 수 없어서다(NFR-INT-02, PR-03).
 */
public enum CalcParamKey {
    /** 적금 이자와 ETF 매매차익에 매기는 세율(TBD-02). */
    TAX_RATE_PCT(Unit.PERCENT, true),
    /** 이미 저축 중인 납입액 토글이 꺼졌을 때의 적금 비중(FR-INP-08, TBD-13). */
    DEFAULT_SAVINGS_RATIO_PCT(Unit.PERCENT, true),
    /** 3구간에 쓰는 관측 기간. 최근 이 개월 수만큼의 한 달 수익률로 경로를 만든다(TBD-01, calc-spec 7-1). */
    SCENARIO_OBSERVATION_MONTHS(Unit.MONTHS, true),
    /** 나빴던 때로 쓸 분위(TBD-01). */
    SCENARIO_LOW_PERCENTILE(Unit.PERCENT, true),
    /** 좋았던 때로 쓸 분위(TBD-01). */
    SCENARIO_HIGH_PERCENTILE(Unit.PERCENT, true),
    /** 부트스트랩 경로 수. 결과를 바꾸는 값이라 버전과 함께 둔다(calc-spec 7-2). */
    BOOTSTRAP_PATH_COUNT(Unit.COUNT, true),
    /** 부트스트랩 난수 시드. 같은 시드면 같은 경로가 나와 결과를 재현할 수 있다(calc-spec 7-2). */
    BOOTSTRAP_SEED(Unit.SEED, true);

    private static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(100);

    private final Unit unit;
    private final boolean isRequired;

    CalcParamKey(Unit unit, boolean isRequired) {
        this.unit = unit;
        this.isRequired = isRequired;
    }

    public Unit unit() {
        return unit;
    }

    public boolean isRequired() {
        return isRequired;
    }

    /** 이 항목에 넣을 수 있는 값인지. 단위가 정하는 범위만 본다. */
    public boolean accepts(BigDecimal value) {
        return switch (unit) {
            case PERCENT -> value.signum() >= 0 && value.compareTo(MAX_PERCENT) <= 0;
            case MONTHS, COUNT -> value.signum() > 0 && isInteger(value);
            case SEED -> value.signum() >= 0 && isInteger(value);
        };
    }

    private static boolean isInteger(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    /** 파라미터 값의 단위. */
    public enum Unit {
        PERCENT,
        MONTHS,
        /** 1 이상의 정수. */
        COUNT,
        /** 0 이상의 정수. */
        SEED
    }
}
