package com.moeun.backend.chat.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.chat.application.AcceptedQuestion;
import com.moeun.backend.chat.application.ChatQuestionService;

@RestController
@RequestMapping("/api/chat")
class ChatController {

    private final ChatQuestionService questions;

    ChatController(ChatQuestionService questions) {
        this.questions = questions;
    }

    @PostMapping("/messages")
    ResponseEntity<AcceptedQuestion> ask(@AuthenticationPrincipal Jwt jwt, @RequestBody SendQuestionRequest request) {
        return ResponseEntity.accepted().body(questions.ask(Long.parseLong(jwt.getSubject()), request.text()));
    }
}
