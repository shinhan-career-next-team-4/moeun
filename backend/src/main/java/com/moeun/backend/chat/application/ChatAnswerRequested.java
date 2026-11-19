package com.moeun.backend.chat.application;

/** 질문·설명 대기 행을 만든 뒤 발행한다. 리스너가 ai-server POST /chat을 불러 결과를 저장한다(모듈 내부 이벤트). */
public record ChatAnswerRequested(long assistantMessageId, long memberId) {
}
