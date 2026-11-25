package com.moeun.backend.chat.web;

/** F-1 요청 본문. 검증은 서비스가 한다(공백뿐·길이 초과가 오류 형식이 다르다). */
record SendQuestionRequest(String text) {
}
