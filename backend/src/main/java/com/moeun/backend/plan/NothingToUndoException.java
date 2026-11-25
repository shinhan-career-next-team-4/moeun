package com.moeun.backend.plan;

import java.util.List;

import org.springframework.http.HttpStatus;

/** 되돌릴 직전 계획이 없다(되돌리기 검사 3). 409 NOTHING_TO_UNDO. 되돌리기는 1단계다(FR-PLAN-04). */
public class NothingToUndoException extends PlanProblemException {

    public NothingToUndoException() {
        super(HttpStatus.CONFLICT, "NOTHING_TO_UNDO", "되돌릴 직전 계획이 없어요.", List.of());
    }
}
