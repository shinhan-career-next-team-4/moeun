package com.moeun.backend.chat.application;

/** ai-server가 답을 주지 못했다(오류 본문, 알 수 없는 응답 형식). 리스너가 메시지를 FAILED로 바꾼다. */
class AiServerException extends RuntimeException {

    AiServerException(String message) {
        super(message);
    }
}
