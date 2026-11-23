package com.moeun.backend.plan;

/**
 * 입력 항목. 범위·필수·단위는 config/rules.yml의 moeun.rules.fields에 있다(CP-02).
 * ETF_PRODUCT_CODE는 숫자 범위가 아니라 적재된 종목인지로 검증하므로 fields에 없다(#154).
 */
public enum InputField {
    GOAL_TYPE,
    GOAL_CUSTOM_NAME,
    GOAL_AMOUNT,
    TARGET_PERIOD_MONTHS,
    HELD_ASSETS,
    MONTHLY_SAVING_AMOUNT,
    SAVINGS_RATE_INPUT_PCT,
    SAVINGS_RATIO_PCT,
    ETF_PRODUCT_CODE;

    /** API JSON의 필드명(camelCase). 오류 응답의 field 값으로 쓴다. */
    public String apiName() {
        StringBuilder name = new StringBuilder();
        boolean upper = false;
        for (char c : name().toLowerCase().toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                name.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return name.toString();
    }
}
