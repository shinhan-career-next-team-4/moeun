package com.moeun.backend.calc;

/**
 * 3구간. 값은 schema.sql의 baseline_scenario CHECK 목록과 같아야 한다.
 * <p>ETF 비중이 0%면 구간이 갈리지 않으므로 결과에는 AVG 하나만 담긴다.
 */
public enum Scenario {
    GOOD,
    AVG,
    BAD
}
