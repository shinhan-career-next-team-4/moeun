package com.moeun.backend.auth;

/**
 * 데모 계정이 초기화를 요청했다. 그 회원의 대화와 계획을 모두 지운다(계정은 남는다).
 * 동기로 발행하므로 리스너가 실패하면 요청도 실패한다. chat이 받아 대화를 지운 뒤 plan의 PlanEraser로 계획을 지운다.
 */
public record DemoReset(long memberId) {
}
