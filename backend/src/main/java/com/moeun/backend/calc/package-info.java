/**
 * 계산. 결과는 저장하지 않고 calc_run에 "무엇으로 계산했는지"만 남긴 뒤 매번 다시 계산한다.
 * <p>테이블: calc_run. 주요 클래스: CalcEngine, ConditionResolver, RatioResolver, AlternativeFinder.
 * ai-server용 POST /internal/calc도 여기 있다.
 * <p>수신 이벤트: plan.PlanChanged → calc_run(PLAN) 기록.
 */
@ApplicationModule(displayName = "계산", allowedDependencies = {"plan", "refdata"})
package com.moeun.backend.calc;

import org.springframework.modulith.ApplicationModule;
