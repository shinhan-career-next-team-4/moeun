/**
 * 계산. 결과는 저장하지 않고 calc_run에 "무엇으로 계산했는지"만 남긴 뒤 매번 다시 계산한다.
 * <p>테이블: calc_run. 주요 클래스: CalcEngine, ConditionResolver, RatioResolver, AlternativeFinder.
 * ai-server용 POST /internal/calc, 대안(POST /api/alternatives)·조건 비교(POST /api/compare) REST도
 * 여기 있다.
 * <p>다른 모듈과 주고받는 계약: CalcInput(계산 조건), CalcResult(시나리오별 결과·가정 보기·데이터 버전).
 * <p>수신 이벤트: plan.PlanChanged → calc_run(PLAN) 기록.
 */
@ApplicationModule(displayName = "계산", allowedDependencies = {"plan", "refdata", "config :: copy"})
package com.moeun.backend.calc;

import org.springframework.modulith.ApplicationModule;
