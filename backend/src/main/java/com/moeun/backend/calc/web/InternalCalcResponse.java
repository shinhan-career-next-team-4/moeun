package com.moeun.backend.calc.web;

import java.util.Map;

import com.moeun.backend.calc.CalcComparison;
import com.moeun.backend.calc.CalcResultView;
import com.moeun.backend.calc.application.CompareConditionsUseCase;

/**
 * {@code POST /internal/calc} 성공 응답(계약 internal-calc C2-3). 결과는 월별 점이 없는 공개 View로 내보낸다.
 *
 * @param calcRunId 이번 계산의 calc_run(CHAT) id. ai-server가 /chat 응답으로 넘긴다
 * @param conditions 두 계산에 실제 쓴 4개 조건
 * @param current 현재 계획 결과
 * @param changed 바꾼 조건 결과
 * @param diff changed − current
 * @param display 표시 문자열 맵. 모델은 금액·기간·비율을 이 문자열로만 말한다
 */
record InternalCalcResponse(long calcRunId, CalcComparison.Conditions conditions, CalcResultView current,
        CalcResultView changed, CalcComparison.Diff diff, Map<String, String> display) {

    static InternalCalcResponse from(CompareConditionsUseCase.ComparisonResult result) {
        CalcComparison comparison = result.comparison();
        return new InternalCalcResponse(result.calcRunId(), comparison.conditions(),
                CalcResultView.from(comparison.current()), CalcResultView.from(comparison.changed()),
                comparison.diff(), result.display());
    }
}
