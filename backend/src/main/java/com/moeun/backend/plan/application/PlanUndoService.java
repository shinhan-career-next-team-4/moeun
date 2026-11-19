package com.moeun.backend.plan.application;

/**
 * 되돌리기(FR-PLAN-04, D-068). 직전 버전 행을 복사해 새 버전으로 넣고 포인터를 옮긴다.
 * 공개 API가 아니다. 호출자는 plan.web의 되돌리기 컨트롤러뿐이다(계약 C12a-8).
 */
public interface PlanUndoService {

    /** @return 갱신된 revision. 응답에 undoAvailable은 없다 */
    long undo(long memberId, long expectedRevision);
}
