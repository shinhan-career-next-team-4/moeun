package com.moeun.backend.calc.web.alternative;

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

/** 대안 값 산출 API 문서(#66). openapi3.yaml의 원천이다. */
@AutoConfigureRestDocs
class AlternativeDocsTest extends AlternativeTestSupport {

    @Test
    void findAlternatives() throws Exception {
        savePlan(MISSING_MONTHLY);

        MvcResult result = mockMvc.perform(post("/api/alternatives").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetPeriodMonths\":48}"))
                .andExpect(status().isOk())
                .andDo(document("alternative-find", resource(ResourceSnippetParameters.builder()
                        .tag("Alternative").summary("대안 값 산출")
                        .description("기준 시나리오로 목표에 못 미치는 내 현재 계획에 월 저축액·기한·비중을 하나씩 바꾼 대안 3개를 "
                                + "돌려준다. 달성하거나 이미 목표를 넘은 계획은 hasAlternatives=false에 빈 목록(대안 없음). "
                                + "targetPeriodMonths는 '기한을 늘리면'의 원하는 기간(개월)이고 없으면 그 카드는 빈 카드다. "
                                + "월 저축액은 현재의 +10%, 비중은 ETF 10%p 증가(적금 -10)이고 입력 범위를 벗어나면 빈 카드다. "
                                + "기간이 1~360 밖이면 422 VALIDATION_FAILED, 계획이 없으면 409 NO_PLAN. 저장하지 않는다.")
                        .requestFields(
                                fieldWithPath("targetPeriodMonths").optional().description("원하는 기간(개월). 1~360"))
                        .responseFields(
                                fieldWithPath("hasAlternatives").description("대안을 보여줄지(달성·초과 계획이면 false)"),
                                fieldWithPath("alternatives[].key").description(
                                        "monthlySavingAmount·targetPeriodMonths·savingsRatioPct 순서"),
                                subsectionWithPath("alternatives[].changes").description(
                                        "{key: 바꾼 값}. '비교하기'가 채울 조건. 빈 카드면 {}"),
                                subsectionWithPath("alternatives[].conditions").optional()
                                        .description("두 계산에 쓴 조건 {current, changed}. 빈 카드면 null"),
                                subsectionWithPath("alternatives[].current").optional()
                                        .description("현재 계획 결과(월별 점 없음). 빈 카드면 null"),
                                subsectionWithPath("alternatives[].changed").optional()
                                        .description("조건 하나를 바꾼 결과(월별 점 없음). 빈 카드면 null"),
                                subsectionWithPath("alternatives[].diff").optional()
                                        .description("changed − current. 빈 카드면 null"),
                                subsectionWithPath("alternatives[].display").description("표시 문자열 맵. 빈 카드면 {}"))
                        .build())))
                .andReturn();

        assertThat(json.readTree(result.getResponse().getContentAsString()).get("alternatives").size())
                .isEqualTo(3);
    }
}
