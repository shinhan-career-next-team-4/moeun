package com.moeun.backend.calc;

/**
 * 3구간 수익률을 정하는 방식. 두 방식을 비교한 뒤 하나를 고르기로 했다(TBD-01 미결).
 * 가정 보기와 engine_version에 어떤 방식으로 계산했는지 남기기 위해 결과에 싣는다.
 */
public enum ScenarioMethod {
    /** 방식 A: 과거 전체 기간의 연평균 복리수익률 하나를 쓴다. */
    CAGR,
    /** 방식 B: 과거 구간을 한 달씩 밀어 얻은 수익률 분포에서 하위 10%·산술평균·상위 90%를 쓴다. */
    ROLLING_DISTRIBUTION
}
