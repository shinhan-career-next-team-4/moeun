package com.moeun.backend.config;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모듈 핸들러가 잡지 않은 오류를 계약 오류 본문(ProblemDetail + code + errors[])으로 바꾼다.
 * 스프링 MVC 자체 예외는 원래 상태를 지키고, 400은 MALFORMED_REQUEST, 5xx는 INTERNAL_ERROR 코드를 싣는다.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없어요.",
                ProblemCode.MALFORMED_REQUEST.name()));
    }

    /** 빈 값 검사 실패(@Valid)는 계약상 422 VALIDATION_FAILED이고 필드마다 REQUIRED 하나를 싣는다. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "입력값을 확인해 주세요.", "VALIDATION_FAILED");
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .map(field -> Map.of("field", field, "reason", "REQUIRED"))
                .toList());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(exception, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty("errors", List.of());
            if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
                problem.setProperty("code", ProblemCode.MALFORMED_REQUEST.name());
            } else if (statusCode.is5xxServerError()) {
                problem.setProperty("code", ProblemCode.INTERNAL_ERROR.name());
            }
        }
        return response;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> internalError(Exception exception) {
        LOG.error("처리하지 못한 예외", exception);
        return ResponseEntity.internalServerError().body(problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "잠시 후 다시 시도해 주세요.", ProblemCode.INTERNAL_ERROR.name()));
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        problem.setProperty("errors", List.of());
        return problem;
    }
}
