package com.moeun.backend.calc.web;

/** /internal/calc 요청 형식이 틀렸다(400 MALFORMED_REQUEST, 계약 C2-2 검사 순서 1·4). 값은 메시지에 남기지 않는다. */
class MalformedInternalCalcRequestException extends RuntimeException {

    MalformedInternalCalcRequestException() {
        super("malformed internal calc request");
    }
}
