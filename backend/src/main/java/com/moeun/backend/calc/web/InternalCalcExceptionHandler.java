package com.moeun.backend.calc.web;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.moeun.backend.calc.application.CalcRejectedException;
import com.moeun.backend.calc.application.Formatter;
import com.moeun.backend.plan.FieldError;

/**
 * {@code /internal/calc} 오류를 계약 오류 본문으로 바꾼다(계약 internal-calc C3). 이 컨트롤러에만 적용한다.
 * <ul>
 *     <li>{@code OUT_OF_RANGE}에 {@code minDisplay}·{@code maxDisplay}를 붙인다. 모델은 범위를 이 문자열로만 말한다.
 *     plan이 calc의 Formatter를 부르면 순환 의존이라 여기서 붙인다</li>
 *     <li>예상하지 못한 예외는 500 {@code CALC_FAILED}이고 detail에 내부 메시지를 싣지 않는다</li>
 * </ul>
 * 같은 검증 오류·예외가 다른 경로(입력 저장, 대화 적용)로 나갈 때는 이 처리가 섞이지 않고 공통 처리기가 맡는다(X-15).
 * <p>plan 처리기 바로 다음 순서다. 이 처리기는 {@code Exception} 전체를 잡으므로, 같은 순서면 plan 예외가 여기서
 * 400·500으로 바뀔 수 있다. plan 예외는 plan 처리기가 계약 코드 그대로 내보낸다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RestControllerAdvice(assignableTypes = InternalCalcController.class)
class InternalCalcExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(InternalCalcExceptionHandler.class);
    private static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    private static final Map<String, String> TITLES = Map.of(
            CalcRejectedException.VALIDATION_FAILED, "입력 범위를 벗어났어요",
            CalcRejectedException.FIELD_NOT_CHANGEABLE, "대화로 바꿀 수 없는 조건이에요",
            CalcRejectedException.NO_PLAN, "계획이 없어요",
            CalcRejectedException.ALREADY_EXCEEDED, "이미 목표를 넘었어요");

    private final Formatter formatter;

    InternalCalcExceptionHandler(Formatter formatter) {
        this.formatter = formatter;
    }

    @ExceptionHandler(CalcRejectedException.class)
    ResponseEntity<ProblemDetail> handleRejected(CalcRejectedException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.status());
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(TITLES.get(exception.code()));
        if (!exception.errors().isEmpty()) {
            problem.setDetail(exception.errors().stream()
                    .map(error -> error.field() + ": " + error.reason())
                    .collect(Collectors.joining(", ")));
        }
        problem.setProperty("code", exception.code());
        problem.setProperty("errors", exception.errors().stream().map(this::toErrorBody).toList());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(MalformedInternalCalcRequestException.class)
    ResponseEntity<ProblemDetail> handleMalformed(MalformedInternalCalcRequestException exception) {
        return ResponseEntity.badRequest().body(malformed(HttpStatus.BAD_REQUEST));
    }

    /** 본문을 JSON으로 읽지 못했다(검사 순서 1). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(malformed(HttpStatus.BAD_REQUEST));
    }

    /**
     * 그 밖의 예외. 스프링 MVC 요청 오류(Content-Type 등)는 계약대로 400 MALFORMED_REQUEST로(C3-1, 이 경로의 4xx 형식
     * 오류는 400 하나뿐), 나머지는 계산 실패다.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        if (exception instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            return ResponseEntity.badRequest().body(malformed(HttpStatus.BAD_REQUEST));
        }
        LOG.error("/internal/calc 계산 실패", exception);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("계산하지 못했어요");
        problem.setProperty("code", "CALC_FAILED");
        problem.setProperty("errors", List.of());
        return ResponseEntity.internalServerError().body(problem);
    }

    private static ProblemDetail malformed(HttpStatus status) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle("요청 형식이 올바르지 않아요");
        problem.setProperty("code", MALFORMED_REQUEST);
        problem.setProperty("errors", List.of());
        return problem;
    }

    /** 오류 하나. 값이 있는 키만 싣고, 범위 오류에는 Formatter의 경계 문자열을 더한다(계약 C3-1). */
    private Map<String, Object> toErrorBody(FieldError error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("field", error.field());
        body.put("reason", error.reason());
        putIfPresent(body, "min", error.min());
        putIfPresent(body, "max", error.max());
        putIfPresent(body, "unit", error.unit());
        putIfPresent(body, "scale", error.scale());
        if (error.min() != null && error.max() != null) {
            body.put("minDisplay", formatBound(error.field(), error.min()));
            body.put("maxDisplay", formatBound(error.field(), error.max()));
        }
        return body;
    }

    /**
     * 경계값은 정확한 값이라 {@code 약}을 붙이지 않는다: 금액 1만원, 기간 0년 1개월, 금리 연 20.00%, 비중 적금 0 : ETF 100,
     * 목표 이름 글자 수 1자(#345).
     */
    private String formatBound(String field, long bound) {
        return switch (field) {
            case "monthlySavingAmount" -> formatter.formatExactAmount(bound);
            case "targetPeriodMonths" -> formatter.formatPeriod((int)bound);
            case "savingsRateInputPct" -> formatter.formatSavingsRate(BigDecimal.valueOf(bound));
            case "savingsRatioPct" -> formatter.formatRatio((int)bound);
            case "goalCustomName" -> bound + "자";
            default -> String.valueOf(bound);
        };
    }

    private static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }
}
