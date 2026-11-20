package com.moeun.backend.chat.web;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.chat.application.ChatExplanationService;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-3 설명 요청. 본문 형식을 직접 검사한다: 공통 처리기는 405·415에 MALFORMED_REQUEST를 싣고, 핸들러 없이 나는 오류는
 * 채팅 처리기가 받지 못한다. 한 핸들러가 본문을 바이트로 받아 미디어 타입·정수 id·뒤따르는 값까지 직접 본다.
 * 형식 오류는 서비스(정리 포함)에 닿기 전에 거절한다.
 */
@RestController
@RequestMapping("/api/chat/explanations")
class ChatExplanationController {

    private static final String CALC_RUN_ID = "calcRunId";

    /** 무시하는 필드(retryOfMessageId 등)의 숫자 길이가 요청의 성공 여부를 가르지 않도록 숫자 길이 제한을 넓힌 읽기 전용 매퍼. */
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNumberLength(100_000).build()).build()).build();

    private final ChatExplanationService explanations;

    ChatExplanationController(ChatExplanationService explanations) {
        this.explanations = explanations;
    }

    /** retryOfMessageId·memberId 같은 다른 필드는 읽지 않는다. 회원은 JWT로만 정한다. */
    @PostMapping
    ResponseEntity<Map<String, Object>> request(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request)
            throws IOException {
        // MVC의 @RequestBody 해석은 Content-Type을 먼저 파싱해 415에 공통 코드를 싣는다. 본문을 직접 읽어 이를 피한다.
        byte[] body = request.getInputStream().readAllBytes();
        if (body.length == 0) {
            throw ExplanationRequestException.malformed();
        }
        requireJson(request.getHeader(HttpHeaders.CONTENT_TYPE));
        long calcRunId = parseCalcRunId(body);
        explanations.request(Long.parseLong(jwt.getSubject()), calcRunId);
        return ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON).body(Map.of());
    }

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    ResponseEntity<Void> unsupportedMethod() {
        throw ExplanationRequestException.methodNotAllowed();
    }

    private static void requireJson(String contentType) {
        if (contentType == null) {
            throw ExplanationRequestException.unsupportedMediaType();
        }
        try {
            if (!MediaType.APPLICATION_JSON.includes(MediaType.parseMediaType(contentType))) {
                throw ExplanationRequestException.unsupportedMediaType();
            }
        } catch (InvalidMediaTypeException e) {
            throw ExplanationRequestException.unsupportedMediaType();
        }
    }

    /** 본문 전체가 객체 하나이고 calcRunId가 long 범위의 JSON 정수여야 한다(문자열·소수·범위 밖은 모두 400). */
    private long parseCalcRunId(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(body);
        } catch (JacksonException e) {
            throw ExplanationRequestException.malformed();
        }
        JsonNode id = root == null || !root.isObject() ? null : root.get(CALC_RUN_ID);
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong()) {
            throw ExplanationRequestException.malformed();
        }
        return id.longValue();
    }
}
