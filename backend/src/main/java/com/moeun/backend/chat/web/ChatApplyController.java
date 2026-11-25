package com.moeun.backend.chat.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.chat.application.ChatApplyService;

import tools.jackson.databind.JsonNode;

/**
 * F-5 대화 비교 적용. 본문은 {@code {expectedRevision}} 하나이고 응답은 {@code {revision}} 하나다. messageId가 정수가 아니면
 * 스프링의 타입 변환 오류가, 파싱할 수 없는 본문이면 읽기 오류가 공통 처리기의 400 MALFORMED_REQUEST가 된다.
 * 회원은 JWT로만 정한다.
 */
@RestController
@RequestMapping("/api/chat/messages/{messageId}/apply")
class ChatApplyController {

    private static final String EXPECTED_REVISION = "expectedRevision";

    private final ChatApplyService applies;

    ChatApplyController(ChatApplyService applies) {
        this.applies = applies;
    }

    @PostMapping
    ChatApplyResponse apply(@AuthenticationPrincipal Jwt jwt, @PathVariable long messageId,
            @RequestBody JsonNode body) {
        long expectedRevision = parseExpectedRevision(body);
        return new ChatApplyResponse(applies.apply(Long.parseLong(jwt.getSubject()), messageId, expectedRevision));
    }

    /** expectedRevision은 long 범위의 JSON 정수여야 한다(문자열·소수·null·누락·범위 밖은 모두 400). */
    private static long parseExpectedRevision(JsonNode body) {
        JsonNode revision = body.isObject() ? body.get(EXPECTED_REVISION) : null;
        if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong()) {
            throw ExplanationRequestException.malformed();
        }
        return revision.longValue();
    }
}
