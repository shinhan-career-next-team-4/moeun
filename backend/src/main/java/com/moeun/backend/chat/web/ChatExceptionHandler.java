package com.moeun.backend.chat.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.moeun.backend.chat.application.ChatRequestException;
import com.moeun.backend.chat.application.ChatValidationException;
import com.moeun.backend.config.copy.CopyBook;

@Order(Ordered.HIGHEST_PRECEDENCE)   // 공통 처리기(config)가 code·errors를 덮어쓰기 전에 본다
@RestControllerAdvice(basePackageClasses = ChatController.class)
class ChatExceptionHandler {

    private final CopyBook copy;

    ChatExceptionHandler(CopyBook copy) {
        this.copy = copy;
    }

    @ExceptionHandler(ChatRequestException.class)
    ProblemDetail chatRequest(ChatRequestException exception) {
        return exception.getBody();
    }

    /** 입력 오류의 안내 문구는 문구표의 같은 코드 title을 쓴다(CP-05). */
    @ExceptionHandler(ChatValidationException.class)
    ProblemDetail validation(ChatValidationException exception) {
        ProblemDetail problem = exception.getBody();
        problem.setDetail(copy.get("error.title.VALIDATION_FAILED"));
        return problem;
    }
}
