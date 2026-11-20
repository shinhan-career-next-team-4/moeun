package com.moeun.backend.chat.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.moeun.backend.auth.SkipRenewal;
import com.moeun.backend.chat.application.AcceptedQuestion;
import com.moeun.backend.chat.application.ChatPollingService;
import com.moeun.backend.chat.application.ChatQuestionService;
import com.moeun.backend.chat.application.PollResult;

@RestController
@RequestMapping("/api/chat")
class ChatController {

    private final ChatQuestionService questions;
    private final ChatPollingService polling;

    ChatController(ChatQuestionService questions, ChatPollingService polling) {
        this.questions = questions;
        this.polling = polling;
    }

    @PostMapping("/messages")
    ResponseEntity<AcceptedQuestion> ask(@AuthenticationPrincipal Jwt jwt, @RequestBody SendQuestionRequest request) {
        return ResponseEntity.accepted().body(questions.ask(Long.parseLong(jwt.getSubject()), request.text()));
    }

    /** afterId가 없으면 복원(최근 50개), 있으면 폴링. 폴링은 토큰 만료를 연장하지 않는다. */
    @SkipRenewal
    @GetMapping("/messages")
    PollResult poll(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String afterId) {
        return polling.poll(Long.parseLong(jwt.getSubject()), parseAfterId(afterId));
    }

    /** 빈 값도 정수가 아니므로 400이다(스프링 기본 변환은 빈 문자열을 null로 바꿔 복원으로 처리한다). */
    private static Long parseAfterId(String afterId) {
        if (afterId == null) {
            return null;
        }
        try {
            return Long.parseLong(afterId);
        } catch (NumberFormatException e) {
            throw new MethodArgumentTypeMismatchException(afterId, Long.class, "afterId", null, e);
        }
    }
}
