package com.moeun.backend.calc.application;

import java.util.Map;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.plan.PlanView;

/**
 * 현재 계획의 결과 한 벌(계약 C12a-6-3). 결과 화면(#30·#31)과 planContext(#78)가 이 하나로 응답을 만든다.
 * <p>계획 스냅샷과 결과가 같은 계획 버전을 가리키도록 계획은 한 번만 읽은 값이다. 컨트롤러가 계획을 따로 다시 읽으면
 * 그 사이 다른 탭의 적용이 끼어 revision과 결과가 다른 버전을 가리킬 수 있다.
 *
 * @param plan 현재 계획 스냅샷(revision, currentVersionId, 입력값, baselineScenario)
 * @param calcRunId 이 결과의 calc_run(PLAN) id. 같은 계획 버전·기준 데이터 버전·엔진 버전이면 같다
 * @param input 계산에 쓴 조건(기본금리·기본 비중 반영 뒤)
 * @param result 계산 결과
 * @param display 표시 문자열 맵(키 접두어 없음, ResultDisplayBuilder)
 */
public record CurrentPlanResult(PlanView plan, long calcRunId, CalcInput input, CalcResult result,
        Map<String, String> display) {

    public CurrentPlanResult {
        display = Map.copyOf(display);
    }
}
