package com.moeun.backend.chat.compare;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.epages.restdocs.apispec.ResourceSnippetParameters;

/** 조건 변경 비교 API 문서(#64). openapi3.yaml의 원천이다. */
@AutoConfigureRestDocs
class ConditionCompareDocsTest extends ConditionCompareTestSupport {

    @Test
    void compareConditions() throws Exception {
        savePlan(300_000L);

        MvcResult result = mockMvc.perform(post("/api/compare").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionChanges\":{\"monthlySavingAmount\":400000,\"targetPeriodMonths\":30}}"))
                .andExpect(status().isOk())
                .andDo(document("chat-compare-conditions", resource(ResourceSnippetParameters.builder()
                        .tag("Chat").summary("조건 변경 비교")
                        .description("현재 계획에 바꾼 조건만 얹어 current·changed·diff를 계산해 돌려준다(FR-CMP-02). "
                                + "계획을 바꾸지 않고 calc_run·대화 메시지도 남기지 않으며 월별 그래프 점은 싣지 않는다. "
                                + "바꿀 수 있는 조건은 goalAmount, monthlySavingAmount, targetPeriodMonths, savingsRatioPct, "
                                + "savingsRateInputPct(값이 null인 키는 무시)다. ETF 비중이 0%가 아니면 "
                                + "current·changed의 scenarios에 GOOD·AVG·BAD 3구간이 오고 0%면 AVG 하나다. "
                                + "허용 밖 키·범위 밖·정수 자리 소수·숫자가 아닌 값·빈 변경은 422 VALIDATION_FAILED(errors에 걸린 "
                                + "필드 전부), 계획 없음 409 NO_PLAN, 바꾼 조건으로도 이미 목표 초과 409 ALREADY_EXCEEDED, "
                                + "본문 형식 오류 400 MALFORMED_REQUEST.")
                        .requestFields(
                                subsectionWithPath("conditionChanges").description("바꿀 조건. 키는 camelCase, 값은 숫자. "
                                        + "etfProductCode만 문자열(적재된 종목 코드, 아니면 422 NOT_AVAILABLE, #154)"))
                        .responseFields(
                                fieldWithPath("applicable").description("지금 계획 기준의 비교인지. 이 API는 항상 true"),
                                fieldWithPath("changedKeys[]").description("바꾼 조건의 키(null이 아닌 값, 요청 순서)"),
                                subsectionWithPath("conditions").description("두 계산에 쓴 5개 조건 {current, changed}"),
                                subsectionWithPath("current").description("현재 계획 결과(월별 점 없음)"),
                                subsectionWithPath("changed").description("조건을 바꾼 결과(월별 점 없음)"),
                                subsectionWithPath("diff").description("changed − current"),
                                subsectionWithPath("display").description("표시 문자열 맵. 금액·기간·비율은 이 문자열로 보여 준다"))
                        .build())))
                .andReturn();

        assertThat(json.readTree(result.getResponse().getContentAsString()).get("changed").isObject()).isTrue();
    }
}
