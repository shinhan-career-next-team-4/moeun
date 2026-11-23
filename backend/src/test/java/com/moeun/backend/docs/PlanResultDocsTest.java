package com.moeun.backend.docs;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.config.TestcontainersConfig;

import jakarta.servlet.http.Cookie;

/** P-3 입력 저장 문서. 이 테스트가 openapi3.yaml의 PUT /api/plan 원천이다. */
@SpringBootTest(properties = {"moeun.demo.accounts=demo01:pw-123456",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@Import(TestcontainersConfig.class)
class PlanResultDocsTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    Cookie cookie;

    @BeforeEach
    void login() throws Exception {
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        cookie = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);
    }

    @Test
    void savePlanDoc() throws Exception {
        mockMvc.perform(put("/api/plan").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":null,\"goalType\":\"HOUSE\",\"goalCustomName\":null,"
                                + "\"goalAmount\":20000000,\"targetPeriodMonths\":36,\"heldAssets\":5000000,"
                                + "\"monthlySavingAmount\":300000,\"savingsRateInputPct\":null}"))
                .andExpect(status().isOk())
                .andDo(document("plan-save", resource(ResourceSnippetParameters.builder()
                        .tag("calc").summary("입력 저장과 결과 보기")
                        .description("입력을 검증·저장하고 같은 트랜잭션에서 계산해 결과를 돌려준다. 계산이 실패하면 저장도 롤백된다. "
                                + "형식 오류 400 MALFORMED_REQUEST, 검증 오류 422 VALIDATION_FAILED, "
                                + "revision 불일치 409 PLAN_CHANGED, 보유 자산 ≥ 목표면 200 + result.status=ALREADY_EXCEEDED.")
                        .requestFields(
                                fieldWithPath("expectedRevision").description("지금 가진 revision. 계획이 없다고 믿으면 null(키 필수)")
                                        .optional(),
                                fieldWithPath("goalType").description("목표 종류 코드(HOUSE, CAR, … CUSTOM)"),
                                fieldWithPath("goalCustomName").description("CUSTOM일 때 목표명(1~100자)").optional(),
                                fieldWithPath("goalAmount").description("목표 금액(원)"),
                                fieldWithPath("targetPeriodMonths").description("목표 기간(개월)"),
                                fieldWithPath("heldAssets").description("보유 자산(원, 0 허용)"),
                                fieldWithPath("monthlySavingAmount").description("월 저축액(원)"),
                                fieldWithPath("savingsRateInputPct").description("적금 금리(연 %). null = 기본금리(키 필수)")
                                        .optional())
                        .responseFields(
                                fieldWithPath("revision").description("새 계획 revision"),
                                fieldWithPath("calcRunId").description("이 결과의 calc_run(PLAN) id. 같은 revision 동안 같다"),
                                fieldWithPath("baselineResultScenario").description("결과 요약 시나리오. 초과면 null")
                                        .optional(),
                                fieldWithPath("undoAvailable")
                                        .description("되돌릴 직전 계획이 있는지('직전 계획으로 되돌리기' 버튼, D-095)"),
                                subsectionWithPath("plan").description("사용자가 입력한 원래 값(원 단위, nullable 그대로)"),
                                fieldWithPath("result.status").description("CALCULATED / ALREADY_EXCEEDED"),
                                fieldWithPath("result.goalAmount").description("목표 금액(그래프 목표선)"),
                                fieldWithPath("result.calcStartMonth").description("계산 시작월(yyyy-MM)"),
                                fieldWithPath("result.targetMonth").description("목표월(yyyy-MM)"),
                                subsectionWithPath("result.scenarios")
                                        .description("GOOD·AVG·BAD(ETF 0%면 AVG). monthlyPoints는 month·afterTaxAsset만"),
                                subsectionWithPath("result.assumptions").description("가정 보기"),
                                subsectionWithPath("display").description("표시 문자열 맵(C1a-6 키·형식)"))
                        .build())));
    }

    @Test
    void currentPlanDoc() throws Exception {
        mockMvc.perform(put("/api/plan").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":null,\"goalType\":\"HOUSE\",\"goalCustomName\":null,"
                                + "\"goalAmount\":20000000,\"targetPeriodMonths\":36,\"heldAssets\":5000000,"
                                + "\"monthlySavingAmount\":300000,\"savingsRateInputPct\":null}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/plan").cookie(cookie))
                .andExpect(status().isOk())
                .andDo(document("plan-current", resource(ResourceSnippetParameters.builder()
                        .tag("calc").summary("현재 계획과 결과 조회")
                        .description("로그인 후 진입·새로고침 때 부른다. 계획이 없으면 409 NO_PLAN. 계획을 한 번만 읽어 "
                                + "그 버전의 calc_run(PLAN)을 쓰고, 없으면 계산·기록한다. 같은 revision 동안 calcRunId가 같다. "
                                + "응답은 PUT /api/plan과 같은 PlanView다.")
                        .responseFields(
                                fieldWithPath("revision").description("현재 계획 revision"),
                                fieldWithPath("calcRunId").description("이 결과의 calc_run(PLAN) id"),
                                fieldWithPath("baselineResultScenario").description("결과 요약 시나리오. 초과면 null")
                                        .optional(),
                                fieldWithPath("undoAvailable")
                                        .description("되돌릴 직전 계획이 있는지('직전 계획으로 되돌리기' 버튼, D-095)"),
                                subsectionWithPath("plan").description("저장된 입력값(원 단위, nullable 그대로)"),
                                fieldWithPath("result.status").description("CALCULATED / ALREADY_EXCEEDED"),
                                fieldWithPath("result.goalAmount").description("목표 금액(그래프 목표선)"),
                                fieldWithPath("result.calcStartMonth").description("계산 시작월(yyyy-MM)"),
                                fieldWithPath("result.targetMonth").description("목표월(yyyy-MM)"),
                                subsectionWithPath("result.scenarios")
                                        .description("GOOD·AVG·BAD(ETF 0%면 AVG). monthlyPoints는 month·afterTaxAsset만"),
                                subsectionWithPath("result.assumptions").description("가정 보기"),
                                subsectionWithPath("display").description("표시 문자열 맵(C1a-6 키·형식)"))
                        .build())));
    }
}
