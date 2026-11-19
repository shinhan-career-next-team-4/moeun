package com.moeun.backend.chat;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.http.MediaType;

import com.epages.restdocs.apispec.ResourceSnippetParameters;

/** F-1 질문 보내기 API 문서(#41). openapi3.yaml의 원천이다. */
@AutoConfigureRestDocs
class ChatQuestionDocsTest extends ChatIntegrationTestSupport {

    @Test
    void sendQuestion() throws Exception {
        AI.respond(200, AiServerStub.ANSWER_OK);
        mockMvc.perform(post("/api/chat/messages").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"월 50만원으로 하면 어때요?\"}"))
                .andExpect(status().isAccepted())
                .andDo(document("chat-send-question", resource(ResourceSnippetParameters.builder()
                        .tag("Chat").summary("질문 보내기")
                        .description("질문과 답변 대기 자리를 저장하고 202로 답한다. 질문 답변이 이미 대기 중이면 409 ANSWER_PENDING, "
                                + "검증 실패는 422 VALIDATION_FAILED(errors[].reason: REQUIRED / OUT_OF_RANGE), "
                                + "깨진 JSON은 400 MALFORMED_REQUEST.")
                        .requestFields(
                                fieldWithPath("text").description("질문 문장(trim 뒤 1~2000자, UTF-16 단위)"))
                        .responseFields(
                                fieldWithPath("userMessageId").description("저장된 질문(USER) 메시지 id"),
                                fieldWithPath("assistantMessageId").description("답변 대기(ASSISTANT, PENDING) 메시지 id"))
                        .build())));

        awaitAnswerEventsCompleted();
    }
}
