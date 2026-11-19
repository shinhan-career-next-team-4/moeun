package com.moeun.backend.plan;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 그사이 계획이 바뀌었다(revision 불일치, 적용 검사 8 또는 조건부 UPDATE 0행). 409 PLAN_CHANGED.
 * 계약 X-10: 본문에 현재 상태를 싣지 않는다. 프론트가 계획을 다시 읽는다.
 * 런타임 예외라 0행일 때 트랜잭션이 롤백되고, 방금 INSERT한 plan_version이 남지 않는다.
 */
public class PlanChangedException extends PlanProblemException {

    public PlanChangedException() {
        super(HttpStatus.CONFLICT, "PLAN_CHANGED", "그사이 계획이 바뀌었어요.", List.of());
    }
}
