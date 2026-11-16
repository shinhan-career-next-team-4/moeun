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
    /** 3구간 분포를 만들 때 한 구간의 길이(TBD-01). */
    SCENARIO_WINDOW_MONTHS(Unit.MONTHS, true),
    /** 나빴던 때로 쓸 분위(TBD-01). */
    SCENARIO_LOW_PERCENTILE(Unit.PERCENT, true),
    /** 좋았던 때로 쓸 분위(TBD-01). */
    SCENARIO_HIGH_PERCENTILE(Unit.PERCENT, true);

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
            case MONTHS -> value.signum() > 0 && value.stripTrailingZeros().scale() <= 0;
        };
    }

    /** 파라미터 값의 단위. */
    public enum Unit {
        PERCENT,
        MONTHS
    }
}
