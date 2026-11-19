package com.moeun.backend.plan;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 적용하려는 비교의 기준 버전이 현재 버전과 다르다(적용 검사 9). 409 STALE_COMPARISON.
 * 되돌리기가 직전 버전을 복사해 새 id를 만들기 때문에(D-068), 되돌리기 전에 만든 비교는 이 검사에 걸린다.
 */
public class StaleBaseVersionException extends PlanProblemException {

    public StaleBaseVersionException() {
        super(HttpStatus.CONFLICT, "STALE_COMPARISON", "비교를 만든 뒤 계획이 바뀌었어요.", List.of());
    }
}
