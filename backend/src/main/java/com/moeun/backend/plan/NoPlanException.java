package com.moeun.backend.plan;

import java.util.List;

import org.springframework.http.HttpStatus;

/** 적용·되돌리기할 계획이 없다(적용 검사 5). 409 NO_PLAN. */
public class NoPlanException extends PlanProblemException {

    public NoPlanException() {
        super(HttpStatus.CONFLICT, "NO_PLAN", "저장된 계획이 없어요.", List.of());
    }
}
