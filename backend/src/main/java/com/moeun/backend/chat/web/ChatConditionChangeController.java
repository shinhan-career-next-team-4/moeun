package com.moeun.backend.chat.web;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.chat.application.ChatConditionChangeService;

/** F-6 대화 조건 누적 비우기(#418). 본문이 없고, 응답은 비운 뒤 상태 {@code {accumulatedKeys: []}}다. 회원은 JWT로만 정한다. */
@RestController
class ChatConditionChangeController {

    private final ChatConditionChangeService conditionChanges;

    ChatConditionChangeController(ChatConditionChangeService conditionChanges) {
        this.conditionChanges = conditionChanges;
    }

    @DeleteMapping("/api/chat/condition-changes")
    ChatConditionChangesResponse clear(@AuthenticationPrincipal Jwt jwt) {
        conditionChanges.clear(Long.parseLong(jwt.getSubject()));
        return new ChatConditionChangesResponse(List.of());
    }

    /** @param accumulatedKeys 지금 쌓인 조건 키. 비운 뒤라 늘 빈 배열이다 */
    record ChatConditionChangesResponse(List<String> accumulatedKeys) {
    }
}
