package com.moeun.backend.chat.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.chat.application.ChatComparisonService;

/** F-4 비교 결과 조회. messageId가 정수가 아니면 스프링의 타입 변환 오류가 공통 처리기의 400 MALFORMED_REQUEST가 된다. */
@RestController
@RequestMapping("/api/chat/messages/{messageId}/comparison")
class ChatComparisonController {

    private final ChatComparisonService comparisons;

    ChatComparisonController(ChatComparisonService comparisons) {
        this.comparisons = comparisons;
    }

    @GetMapping
    ChatComparisonResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long messageId) {
        return ChatComparisonResponse.from(comparisons.find(Long.parseLong(jwt.getSubject()), messageId));
    }
}
