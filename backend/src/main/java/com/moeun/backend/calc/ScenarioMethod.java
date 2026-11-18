package com.moeun.backend.calc;

/**
 * 3구간 수익률을 정하는 방식. 두 방식을 비교한 뒤 하나를 고르기로 했다(TBD-01, calc-spec 7절).
 * 가정 보기와 engine_version에 어떤 방식으로 계산했는지 남기기 위해 결과에 싣는다.
 */
public enum ScenarioMethod {
    /** 방식 A: 과거 전체 기간의 연평균 복리수익률 하나를 쓴다. */
    CAGR,
    /**
     * 방식 B: 부트스트랩. 과거 한 달 수익률을 복원 추출로 이어 붙인 경로를 여러 개 만들고, 시점마다 하위 10%·산술평균·상위 90%를 쓴다.
     * 이름은 ai-server 문구표 키(scenarioMethod.ROLLING_DISTRIBUTION)와 계약 문서가 쓰고 있어 그대로 둔다.
     */
    ROLLING_DISTRIBUTION
}
