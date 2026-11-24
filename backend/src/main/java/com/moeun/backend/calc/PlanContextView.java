package com.moeun.backend.calc;

import java.util.Map;

/**
 * ai-server {@code POST /chat}에 보내는 planContext(계약 C1a-2). 한 계산 기록(calc_run(PLAN))의 조건·결과·표시 문자열이다.
 * <p>최상위 키는 이 4개뿐이고 회원 식별 값은 타입에 아예 없다(D-030). 가정은 {@code result.assumptions}에 있다.
 * 결과는 {@code /internal/calc}의 {@code current}와 같은 {@link CalcResultView}다(시나리오에 월별 점 없음).
 *
 * @param calcRunId 이 컨텍스트를 계산한 calc_run(PLAN) id. EXPLAIN에서 ai-server가 요청 값과 대조한다
 * @param conditions 계산에 실제 쓴 조건
 * @param result 계산 결과
 * @param display 표시 문자열(접두어 없음, 조건은 {@code conditions.}). 공유 빌더가 만든다
 */
public record PlanContextView(long calcRunId, ConditionsView conditions, CalcResultView result,
        Map<String, String> display) {

    public PlanContextView {
        display = Map.copyOf(display);
    }

    /** 계산 조건·계획의 ETF 종목·결과와 이미 만든 display로 컨텍스트를 만든다. 숫자를 새로 만들지 않는다. */
    public static PlanContextView of(long calcRunId, CalcInput input, String etfProductCode, CalcResult result,
            Map<String, String> display) {
        return new PlanContextView(calcRunId, ConditionsView.from(input, etfProductCode), CalcResultView.from(result),
                display);
    }
}
