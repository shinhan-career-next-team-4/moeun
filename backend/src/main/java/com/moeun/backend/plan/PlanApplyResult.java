package com.moeun.backend.plan;

/** 적용 결과(계약 C12b-7-1). previousVersionId는 적용으로 밀려난 버전, revision은 갱신된 값이다. */
public record PlanApplyResult(long planVersionId, long previousVersionId, long revision) {
}
