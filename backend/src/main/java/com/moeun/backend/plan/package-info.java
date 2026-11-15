/**
 * 내 계획. 조건 스냅샷(plan_version, INSERT 전용)과 현재 계획 포인터(plan, revision 낙관적 락).
 * <p>테이블: plan_version, plan. 주요 클래스: GoalType, InputField, ChangePath, ConditionOverride,
 * ConditionValidator, PlanInputValidator (설정 config/rules.yml).
 * <p>발행 이벤트: PlanChanged (저장·적용·되돌리기).
 */
@ApplicationModule(displayName = "내 계획", allowedDependencies = {})
package com.moeun.backend.plan;

import org.springframework.modulith.ApplicationModule;
