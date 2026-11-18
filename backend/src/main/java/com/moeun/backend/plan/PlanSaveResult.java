package com.moeun.backend.plan;

/** 계획 저장·적용 결과. */
public record PlanSaveResult(long planVersionId, long revision) {
}
