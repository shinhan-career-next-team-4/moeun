/**
 * 계산. 결과는 저장하지 않고 calc_run에 "무엇으로 계산했는지"만 남긴 뒤 매번 다시 계산한다.
 * <p>테이블: calc_run. 주요 클래스: CalcEngine, ConditionResolver, RatioResolver, AlternativeFinder.
 * ai-server용 POST /internal/calc, 대안(POST /api/alternatives)·조건 비교(POST /api/compare) REST도
 * 여기 있다.
 * <p>공개 API는 역할별 named interface다(#311). 루트에는 이 셋과 그 시그니처에 나오는 타입(CalcInput, CalcResult,
 * 뷰·시나리오 타입)만 두고, calc 안에서만 쓰는 타입은 하위 패키지에 둔다.
 * <ul>
 *   <li>{@code plan-context}: PlanContextProvider(planContext, 계약 C1a-10)</li>
 *   <li>{@code chat-comparison}: ChatComparisonQuery(대화 비교 재계산, H-8b·C12b-6)</li>
 *   <li>{@code calc-run-etf}: CalcRunEtfQuery(계산 기록의 ETF 사용 여부, #305)</li>
 * </ul>
 * <p>수신 이벤트: plan.PlanChanged → calc_run(PLAN) 기록.
 */
@ApplicationModule(displayName = "계산", allowedDependencies = {"plan", "refdata", "config :: copy"})
package com.moeun.backend.calc;

import org.springframework.modulith.ApplicationModule;
