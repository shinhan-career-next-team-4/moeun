package com.moeun.backend.calc.web;

/** 그 회원의 저장된 계획이 없다(409 NO_PLAN, 계약 C12a-6-4). */
class NoPlanException extends RuntimeException {

    NoPlanException() {
        super("no saved plan");
    }
}
