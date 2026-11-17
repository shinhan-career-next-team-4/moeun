package com.moeun.backend.plan.application;

import java.util.Optional;

/** 그사이 계획이 바뀌었거나(revision 불일치) 적용·되돌리기할 계획이 없다. 컨트롤러가 409 + 현재 계획으로 바꾼다. */
public class PlanConflictException extends RuntimeException {

    private final transient PlanView current;

    public PlanConflictException(Optional<PlanView> current) {
        super("plan conflict");
        this.current = current.orElse(null);
    }

    public Optional<PlanView> current() {
        return Optional.ofNullable(current);
    }
}
