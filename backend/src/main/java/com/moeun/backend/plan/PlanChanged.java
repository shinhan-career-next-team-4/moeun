package com.moeun.backend.plan;

/** 내 계획이 바뀌었다(저장·적용·되돌리기 커밋). action: INPUT / APPLY / UNDO. beforeVersionId는 첫 저장이면 null. */
public record PlanChanged(long memberId, String action, Long beforeVersionId, long afterVersionId, long revision) {
}
