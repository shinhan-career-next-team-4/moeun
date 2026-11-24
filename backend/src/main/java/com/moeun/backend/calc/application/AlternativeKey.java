package com.moeun.backend.calc.application;

/** 대안 카드가 바꾸는 조건 하나(FR-ALT-02). 응답 키는 {@link #apiName()}이고 카드 표시 순서가 선언 순서다. */
public enum AlternativeKey {
    MONTHLY_SAVING_AMOUNT("monthlySavingAmount"),
    TARGET_PERIOD_MONTHS("targetPeriodMonths"),
    SAVINGS_RATIO_PCT("savingsRatioPct");

    private final String apiName;

    AlternativeKey(String apiName) {
        this.apiName = apiName;
    }

    public String apiName() {
        return apiName;
    }
}
