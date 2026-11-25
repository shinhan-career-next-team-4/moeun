package com.moeun.backend.chat;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.restdocs.payload.JsonFieldType;

import com.epages.restdocs.apispec.ResourceSnippetParameters;

/** F-6 대화 조건 누적 비우기 API 문서(#418). openapi3.yaml의 원천이다. */
@AutoConfigureRestDocs
class ChatConditionChangesDocsTest extends ChatComparisonTestSupport {

    @Test
    void clearConditionChanges() throws Exception {
        mockMvc.perform(delete("/api/chat/condition-changes").cookie(cookie))
                .andExpect(status().isOk())
                .andDo(document("chat-clear-condition-changes", resource(ResourceSnippetParameters.builder()
                        .tag("Chat").summary("대화 조건 누적 비우기")
                        .description("대화 세션이 끝났을 때(대화창 닫기) 대화에서 쌓인 조건의 변경 목록을 비운다(D-103). "
                                + "다음 질문은 내 계획을 기준으로 계산한다. 이미 비어 있어도 200이다. 로그인하지 않으면 "
                                + "401 UNAUTHENTICATED.")
                        .responseFields(fieldWithPath("accumulatedKeys").type(JsonFieldType.ARRAY)
                                .description("비운 뒤 쌓인 조건 키. 늘 빈 배열이다"))
                        .build())));
    }
}
