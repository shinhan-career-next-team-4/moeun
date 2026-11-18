package com.moeun.backend.plan;

/** 대안·비교·대화 조건 적용 공개 서비스. */
public interface PlanApplyService {

    PlanSaveResult apply(long memberId, ChangePath path, ConditionOverride changes, long expectedRevision);
}
