package com.moeun.backend.chat;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.test.web.servlet.MvcResult;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.moeun.backend.plan.ConditionOverride;

/** F-4 비교 결과 조회 API 문서(#44). openapi3.yaml의 원천이다. */
@AutoConfigureRestDocs
class ChatComparisonDocsTest extends ChatComparisonTestSupport {

    @Test
    void getComparison() throws Exception {
        savePlan(memberId, null, 300_000L);
        Recalc recalc = recalc(memberId, new ConditionOverride(500_000L, null, null, null, null, null),
                "{\"monthlySavingAmount\":500000}");

        MvcResult result = mockMvc.perform(get("/api/chat/messages/{messageId}/comparison", recalc.messageId())
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andDo(document("chat-get-comparison", resource(ResourceSnippetParameters.builder()
                        .tag("Chat").summary("비교 결과 조회")
                        .description("RECALC_RESULT 메시지의 비교(current·changed·diff)를 그 메시지의 calc_run(CHAT) 기준으로 "
                                + "다시 계산해 돌려준다. 저장하지 않고 월별 그래프 점은 싣지 않는다. applicable은 내 가장 최근 "
                                + "COMPLETED RECALC_RESULT이고 그 기록의 계획 버전이 현재 계획 버전일 때만 true다. "
                                + "없는·남의·RECALC_RESULT가 아니거나 COMPLETED가 아닌 메시지는 모두 404 NOT_FOUND, "
                                + "messageId가 정수가 아니면 400 MALFORMED_REQUEST. 저장값을 변환할 수 없으면 500 INTERNAL_ERROR.")
                        .pathParameters(parameterWithName("messageId").description("RECALC_RESULT 메시지 id"))
                        .responseFields(
                                fieldWithPath("messageId").description("조회한 메시지 id"),
                                fieldWithPath("applicable").description("지금 이 비교를 적용할 수 있는지"),
                                fieldWithPath("changedKeys[]").description("바꾼 조건의 키(순서 보장 없음, 집합으로 쓴다)"),
                                subsectionWithPath("conditions").description("두 계산에 쓴 5개 조건 {current, changed}"),
                                subsectionWithPath("current").description("현재 계획 결과(월별 점 없음)"),
                                subsectionWithPath("changed").description("조건을 바꾼 결과(월별 점 없음)"),
                                subsectionWithPath("diff").description("changed − current"),
                                subsectionWithPath("display").description("표시 문자열 맵"))
                        .build())))
                .andReturn();

        assertThat(json.readTree(result.getResponse().getContentAsString()).get("applicable").asBoolean()).isTrue();
    }
}
