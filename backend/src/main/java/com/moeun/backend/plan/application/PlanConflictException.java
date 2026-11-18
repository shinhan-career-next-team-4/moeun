package com.moeun.backend.plan.application;

import java.util.Optional;

import com.moeun.backend.plan.PlanView;

/**
 * 그사이 계획이 바뀌었거나(revision 불일치) 적용·되돌리기할 계획이 없다. 컨트롤러가 409 + code + 현재 계획으로 바꾼다.
 * code는 오류 본문에 그대로 실리는 계약 코드다.
 */
public class PlanConflictException extends RuntimeException {

    public static final String PLAN_CHANGED = "PLAN_CHANGED";
    public static final String NO_PLAN = "NO_PLAN";
    public static final String NOTHING_TO_UNDO = "NOTHING_TO_UNDO";

    private final String code;
    private final transient PlanView current;

    public PlanConflictException(String code, Optional<PlanView> current) {
        super("plan conflict: " + code);
        this.code = code;
        this.current = current.orElse(null);
    }

    public String code() {
        return code;
    }

    public Optional<PlanView> current() {
        return Optional.ofNullable(current);
    }
}
