package com.moeun.backend.chat;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.epages.restdocs.apispec.ResourceSnippetParameters;

/** F-3 설명 요청 API 문서(#43). openapi3.yaml의 원천이다. */
@AutoConfigureRestDocs
class ChatExplanationDocsTest extends ChatIntegrationTestSupport {

    @Test
    void requestExplanation() throws Exception {
        long calcRun = insertCalcRun(memberId, "PLAN");
        long planVersion = jdbc.sql("SELECT plan_version_id FROM calc_run WHERE id = ?").param(calcRun)
                .query(Long.class).single();
        jdbc.sql("DELETE FROM plan WHERE member_id = ?").param(memberId).update();
        jdbc.sql("INSERT INTO plan (member_id, current_version_id, baseline_scenario) VALUES (?, ?, 'AVG')")
                .params(memberId, planVersion).update();

        MvcResult result = mockMvc.perform(post("/api/chat/explanations").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calcRunId\":" + calcRun + "}"))
                .andExpect(status().isAccepted())
                .andDo(document("chat-request-explanation", resource(ResourceSnippetParameters.builder()
                        .tag("Chat").summary("설명 요청")
                        .description("내 계획의 계산 기록(PLAN)에 대한 AI 설명 대기 자리를 저장하고 202 {}로 답한다. "
                                + "같은 calcRunId의 설명이 이미 대기 중이면 새 행 없이 202. 질문 답변 대기로 막지 않는다. "
                                + "없는·남의·PLAN이 아닌 기록은 모두 404 NOT_FOUND, calcRunId가 정수가 아니거나 본문이 "
                                + "객체가 아니면 400 MALFORMED_REQUEST.")
                        .requestFields(
                                fieldWithPath("calcRunId").description("결과 화면이 그린 계산 기록(PLAN) id"))
                        .build())))
                .andReturn();

        assertThat(JSON.readTree(result.getResponse().getContentAsString()).size()).isZero();
    }
}
