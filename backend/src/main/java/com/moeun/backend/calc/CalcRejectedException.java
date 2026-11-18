package com.moeun.backend.calc;

import java.util.List;

import com.moeun.backend.plan.FieldError;

/**
 * 계산을 거절했다(계약 internal-calc C3-2). HTTP 상태·코드·필드별 오류를 담고, 받는 컨트롤러가 오류 본문으로 바꾼다.
 * 요청 값(금융 값)은 메시지·오류에 담지 않는다.
 */
public class CalcRejectedException extends RuntimeException {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String FIELD_NOT_CHANGEABLE = "FIELD_NOT_CHANGEABLE";
    public static final String NO_PLAN = "NO_PLAN";
    public static final String ALREADY_EXCEEDED = "ALREADY_EXCEEDED";

    private static final int UNPROCESSABLE = 422;
    private static final int CONFLICT = 409;

    private final int status;
    private final String code;
    private final transient List<FieldError> errors;

    private CalcRejectedException(int status, String code, List<FieldError> errors) {
        super("calc rejected: " + code);
        this.status = status;
        this.code = code;
        this.errors = List.copyOf(errors);
    }

    /** 범위·정수·소수 자릿수·빈 변경(422 VALIDATION_FAILED). 걸린 필드를 모두 담는다. */
    public static CalcRejectedException validationFailed(List<FieldError> errors) {
        return new CalcRejectedException(UNPROCESSABLE, VALIDATION_FAILED, errors);
    }

    /** 대화로 바꿀 수 없는 키(422 FIELD_NOT_CHANGEABLE). errors에는 허용 밖 키만 담는다. */
    public static CalcRejectedException fieldNotChangeable(List<FieldError> errors) {
        return new CalcRejectedException(UNPROCESSABLE, FIELD_NOT_CHANGEABLE, errors);
    }

    public static CalcRejectedException noPlan() {
        return new CalcRejectedException(CONFLICT, NO_PLAN, List.of());
    }

    /** 현재 계획이 이미 목표를 넘었다. 대화로 목표·보유 자산을 바꿀 수 없어 비교가 의미 없다(409). */
    public static CalcRejectedException alreadyExceeded() {
        return new CalcRejectedException(CONFLICT, ALREADY_EXCEEDED, List.of());
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }
}
