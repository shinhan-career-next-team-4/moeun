/**
 * 내 계획. 조건 스냅샷(plan_version, INSERT 전용)과 현재 계획 포인터(plan, revision 낙관적 락).
 * <p>테이블: plan_version, plan. 주요 클래스: GoalType, InputField, ChangePath, ConditionOverride,
 * PlanInput, PlanView, PlanSaveResult, PlanSaveService, PlanApplyService, PlanApplyResult,
 * ConditionValidator, PlanInputValidator (설정 config/rules.yml).
 * <p>EtfProductCatalog: 고를 수 있는 ETF 종목인지 묻는 포트(#154). plan은 refdata에 의존하지 않으므로 calc가 구현하고,
 * plan을 띄우려면 이 빈이 있어야 한다(calc 없이 plan만 띄우는 테스트는 테스트 빈을 넣는다).
 * <p>오류 예외(ErrorResponseException 상속, code·errors를 직접 담음): NoPlanException, PlanChangedException,
 * NothingToUndoException, StaleBaseVersionException, ConditionViolationException.
 * <p>발행 이벤트: PlanChanged (저장·적용·되돌리기).
 */
@ApplicationModule(displayName = "내 계획", allowedDependencies = {})
package com.moeun.backend.plan;

import org.springframework.modulith.ApplicationModule;
