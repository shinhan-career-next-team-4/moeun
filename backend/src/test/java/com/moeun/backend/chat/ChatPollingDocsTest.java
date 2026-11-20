package com.moeun.backend.chat;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;

import com.epages.restdocs.apispec.ResourceSnippetParameters;

/** F-2 메시지 복원·폴링 API 문서(#42). openapi3.yaml의 원천이다. */
@AutoConfigureRestDocs
class ChatPollingDocsTest extends ChatIntegrationTestSupport {

    @Test
    void pollMessages() throws Exception {
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "월 50만원으로 하면 어때요?");
        insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "답변이에요.");
        insertMessage(memberId, "ASSISTANT", "ANSWER", "PENDING", null);

        mockMvc.perform(get("/api/chat/messages").cookie(cookie).param("afterId", String.valueOf(question - 1)))
                .andExpect(status().isOk())
                .andDo(document("chat-poll-messages", resource(ResourceSnippetParameters.builder()
                        .tag("Chat").summary("메시지 복원·폴링")
                        .description("afterId가 없으면 최근 50개를 id 오름차순으로(복원), 있으면 id > afterId인 자기 메시지 "
                                + "전부를 오름차순으로(폴링) 준다. 오래된 PENDING(60초)을 먼저 FAILED로 정리한다. "
                                + "PENDING은 messageType·content가 null이고 FAILED는 messageType=FAILURE에 실패 안내 문구다. "
                                + "afterId가 정수가 아니면 400 MALFORMED_REQUEST. 인증 없음·만료는 401 UNAUTHENTICATED. "
                                + "이 호출은 토큰 만료를 연장하지 않는다.")
                        .queryParameters(parameterWithName("afterId").optional()
                                .description("마지막으로 받은 메시지 id. 없으면 복원"))
                        .responseFields(
                                fieldWithPath("hasPending").description("그 회원에게 PENDING이 하나라도 있음(목록 밖 포함)"),
                                fieldWithPath("answerPending").description("질문 답변 대기(PENDING, ANSWER) 여부"),
                                fieldWithPath("messages[]").description("id 오름차순 메시지"),
                                fieldWithPath("messages[].id").description("메시지 id"),
                                fieldWithPath("messages[].role").description("USER / ASSISTANT"),
                                fieldWithPath("messages[].messageType").optional()
                                        .description("메시지 유형. PENDING이면 null, FAILED면 FAILURE"),
                                fieldWithPath("messages[].status").description("PENDING / COMPLETED / FAILED"),
                                fieldWithPath("messages[].content").optional()
                                        .description("본문. PENDING이면 null"),
                                fieldWithPath("messages[].citations[]").optional()
                                        .description("출처(순서가 순위). 없으면 빈 배열"),
                                fieldWithPath("messages[].createdAt").description("생성 시각(ISO-8601)"))
                        .build())));
    }
}
