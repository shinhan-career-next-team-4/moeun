package com.moeun.backend.chat;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.PlanSaveResult;

/** F-5 대화 적용 API 문서(#45). openapi3.yaml의 원천이다. */
@AutoConfigureRestDocs
class ChatApplyDocsTest extends ChatComparisonTestSupport {

    @Test
    void applyComparison() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, new ConditionOverride(500_000L, null, null, null, null, null),
                "{\"monthlySavingAmount\":500000}");

        MvcResult result = mockMvc.perform(post("/api/chat/messages/{messageId}/apply", recalc.messageId())
                        .cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + saved.revision() + "}"))
                .andExpect(status().isOk())
                .andDo(document("chat-apply-comparison", resource(ResourceSnippetParameters.builder()
                        .tag("Chat").summary("대화 비교 결과 적용")
                        .description("RECALC_RESULT 메시지에 저장된 바꾼 조건을 확인창 없이 내 계획에 적용한다(source=CHAT, 시작월 유지). "
                                + "화면에서 조건값을 다시 받지 않는다. 검사 순서: 본문·messageId 형식(400 MALFORMED_REQUEST) → "
                                + "없는·남의·RECALC_RESULT가 아니거나 COMPLETED가 아닌 메시지(404 NOT_FOUND) → 내 가장 최근 "
                                + "COMPLETED RECALC_RESULT가 아님(409 STALE_COMPARISON) → 계획 없음(409 NO_PLAN) → 저장값 검증"
                                + "(422 VALIDATION_FAILED·FIELD_NOT_CHANGEABLE) → revision 불일치(409 PLAN_CHANGED) → 비교의 "
                                + "기준 버전이 현재와 다름(409 STALE_COMPARISON).")
                        .pathParameters(parameterWithName("messageId").description("RECALC_RESULT 메시지 id"))
                        .requestFields(fieldWithPath("expectedRevision")
                                .description("확인창을 연 시점에 프론트가 아는 plan.revision"))
                        .responseFields(fieldWithPath("revision").description("적용 뒤 새 plan.revision"))
                        .build())))
                .andReturn();

        assertThat(json.readTree(result.getResponse().getContentAsString()).get("revision").asLong())
                .isEqualTo(saved.revision() + 1);
    }
}
