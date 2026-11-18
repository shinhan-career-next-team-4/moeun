package com.moeun.backend.plan.application;

/**
 * 그사이 계획이 바뀌었거나(revision 불일치) 적용·되돌리기할 계획이 없다. 컨트롤러가 409 + code로 바꾼다.
 * 계약 C12a-5-4: 본문에 현재 상태를 싣지 않는다. 프론트가 loadPlan()으로 다시 읽는다(X-10).
 * code는 오류 본문에 그대로 실리는 계약 코드다.
 */
public class PlanConflictException extends RuntimeException {

    public static final String PLAN_CHANGED = "PLAN_CHANGED";
    public static final String NO_PLAN = "NO_PLAN";
    public static final String NOTHING_TO_UNDO = "NOTHING_TO_UNDO";

    private final String code;
    public PlanConflictException(String code) {
        super("plan conflict: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
