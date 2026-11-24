package com.moeun.backend.calc.web.compare;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 조건 변경 비교 오류 본문을 예외가 든 그대로 내보낸다. 이 컨트롤러에만 적용한다.
 * 공통 처리기(config)가 ErrorResponseException의 code·errors를 덮어쓰기 전에 본다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ConditionCompareController.class)
class CompareExceptionHandler {

    @ExceptionHandler(CompareRequestException.class)
    ProblemDetail compareRequest(CompareRequestException exception) {
        return exception.getBody();
    }
}
