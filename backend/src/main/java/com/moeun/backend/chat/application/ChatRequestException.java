package com.moeun.backend.chat.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 채팅 요청 오류. 본문은 ProblemDetail + code + errors[](계약 C12a-5-4). 웹 계층의 채팅 처리기가 그대로 내보낸다. */
public class ChatRequestException extends ErrorResponseException {

    /** 고정 안내 문구(detail)는 넣지 않는다. 필요하면 웹 처리기가 문구표(CP-05)에서 찾아 채운다. */
    protected ChatRequestException(HttpStatus status, String code, List<?> errors) {
        super(status, problem(status, code, errors), null);
    }

    private static ProblemDetail problem(HttpStatus status, String code, List<?> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        problem.setProperty("errors", errors);
        return problem;
    }
}
