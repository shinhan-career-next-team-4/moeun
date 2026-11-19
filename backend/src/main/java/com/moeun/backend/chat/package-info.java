/**
 * AI 대화. 메시지를 영속 저장하고 ai-server POST /chat을 비동기로 호출한다.
 * <p>테이블: chat_message, chat_message_citation.
 * <p>내부 이벤트: ChatAnswerRequested → ai-server 호출 후 PENDING을 COMPLETED/FAILED로 바꾼다.
 */
@ApplicationModule(displayName = "AI 대화", allowedDependencies = {"calc", "rag", "plan", "auth", "config :: copy"})
package com.moeun.backend.chat;

import org.springframework.modulith.ApplicationModule;
