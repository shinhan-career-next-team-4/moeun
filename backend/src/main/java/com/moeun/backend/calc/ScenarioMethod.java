package com.moeun.backend.calc;

/**
 * 3구간 수익률을 정하는 방식(TBD-01, D-006, calc-spec 7절). 가정 보기와 engine_version에 어떤 방식으로 계산했는지
 * 남기기 위해 결과에 싣는다.
 * <p>두 방식의 비교는 끝났고(#162, docs/calc-report.md) 운영 계산은 늘 {@link #ROLLING_DISTRIBUTION}이다.
 * ETF 0%면 구간이 갈리지 않아 방식이 없다(null). {@link #CAGR}는 엔진 결과에 나오지 않는다.
 * <p>값과 이름은 바꾸지 않는다. 문구표 {@code scenarioMethod.*}, ai-server 코드 목록, 계약 예시가 이 이름을 쓴다(G-14).
 */
public enum ScenarioMethod {
    /**
     * 방식 A(비교용): 3구간과 같은 관측 기간(최근 120개월)의 첫 종가와 마지막 종가로 구한 월 수익률 하나를 매달 쓴다.
     * 구간이 갈리지 않는다. 비교 리포트에서만 쓰고, 엔진을 고치지 않고 같은 수익률 시계열을 넣어 구한다(calc-spec 7-4).
     */
    CAGR,
    /**
     * 방식 B(운영): 부트스트랩. 최근 120개월 한 달 수익률을 복원 추출로 이어 붙인 경로를 여러 개 만들고, 시점마다
     * 하위 10%·산술평균·상위 90%를 쓴다(calc-spec 7-2·7-3).
     * 이름은 ai-server 문구표 키(scenarioMethod.ROLLING_DISTRIBUTION)와 계약 문서가 쓰고 있어 그대로 둔다.
     */
    ROLLING_DISTRIBUTION
}
