package com.moeun.backend.calc.web;

/** 입력 저장 요청의 형식이 틀렸다(400 MALFORMED_REQUEST, 계약 C12a-5-2 순서 1). 값은 메시지에 남기지 않는다. */
class MalformedPlanRequestException extends RuntimeException {

    MalformedPlanRequestException() {
        super("malformed plan request");
    }
}
