package com.moeun.backend.docs;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.test.web.servlet.MockMvc;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.config.TestcontainersConfig;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = "moeun.demo.accounts=demo01:pw-123456")
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@Import(TestcontainersConfig.class)
class PlanDocsTest {

    private static final String PLAN_BODY = """
            {"goalType":"JEONSE","goalAmount":50000000,"targetPeriodMonths":36,"heldAssets":6000000,
             "monthlySaving":2000000,"savingsRatePct":3.2,"existingSavingsMonthly":1000000,
             "existingEtfMonthly":200000,"ratioChoice":"KEEP_CURRENT"}""";

    private static final List<FieldDescriptor> INPUT_FIELDS = List.of(
            fieldWithPath("goalType").description("목표 종류: JEONSE, WEDDING, INDEPENDENCE, TRAVEL, CUSTOM"),
            fieldWithPath("goalCustomName").description("직접 입력 목표명(CUSTOM일 때 필수, 100자)").optional(),
            fieldWithPath("goalAmount").description("목표 금액(원, 1만원 단위)"),
            fieldWithPath("targetPeriodMonths").description("목표 기간(개월). null = 기간 미정").optional(),
            fieldWithPath("heldAssets").description("지금 이 목표에 쓸 수 있는 돈(원)"),
            fieldWithPath("monthlySaving").description("월 저축 가능 금액(원). null = 잘 모르겠어요").optional(),
            fieldWithPath("savingsRatePct").description("적금 금리(연, 세전, %). null = 기본금리").optional(),
            fieldWithPath("existingSavingsMonthly").description("기존 적금 월 납입액(원). null = 토글 OFF").optional(),
            fieldWithPath("existingEtfMonthly").description("기존 ETF 월 납입액(원). null = 토글 OFF").optional(),
            fieldWithPath("ratioChoice").description("BASE(기준 비중) / KEEP_CURRENT(지금 비중 유지)").optional());

    private static final List<FieldDescriptor> VIEW_FIELDS = concat(INPUT_FIELDS, List.of(
            fieldWithPath("savingsRatioPct").description("저장된 적금 비중(%). null = 기준 비중").optional(),
            fieldWithPath("calcStartMonth").description("계산 시작 월(yyyy-MM, 처음 저장한 달)"),
            fieldWithPath("baselineScenario").description("기준 시나리오 GOOD/AVG/BAD"),
            fieldWithPath("revision").description("계획 버전. 적용·되돌리기 요청에 expectedRevision으로 보낸다"),
            fieldWithPath("canUndo").description("되돌릴 직전 계획이 있는지"),
            fieldWithPath("updatedAt").description("현재 계획이 만들어진 시각(ISO-8601)")));

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    Cookie cookie;

    @BeforeEach
    void login() throws Exception {
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        cookie = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);
    }

    private void savePlan() throws Exception {
        mockMvc.perform(post("/api/plan").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(PLAN_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void savePlanDoc() throws Exception {
        mockMvc.perform(post("/api/plan").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(PLAN_BODY))
                .andExpect(status().isOk())
                .andDo(document("plan-save", resource(ResourceSnippetParameters.builder()
                        .tag("Plan").summary("입력 화면 저장(결과 보기)")
                        .description("검증 오류는 400 + errors[{field, code}]. 저장 후 PlanChanged(INPUT) 발행.")
                        .requestFields(INPUT_FIELDS).responseFields(VIEW_FIELDS).build())));
    }

    @Test
    void getPlanDoc() throws Exception {
        savePlan();
        mockMvc.perform(get("/api/plan").cookie(cookie))
                .andExpect(status().isOk())
                .andDo(document("plan-get", resource(ResourceSnippetParameters.builder()
                        .tag("Plan").summary("현재 내 계획").description("계획이 없으면 404.")
                        .responseFields(VIEW_FIELDS).build())));
    }

    @Test
    void applyPlanDoc() throws Exception {
        savePlan();
        mockMvc.perform(post("/api/plan/apply").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"COMPARE\",\"changes\":{\"monthlySaving\":1500000},"
                                + "\"expectedRevision\":1}"))
                .andExpect(status().isOk())
                .andDo(document("plan-apply", resource(ResourceSnippetParameters.builder()
                        .tag("Plan").summary("바꾼 조건을 내 계획으로 적용")
                        .description("revision이 다르면 409 + plan(현재 계획). 검증 오류는 400.")
                        .requestFields(
                                fieldWithPath("source").description("ALTERNATIVE / COMPARE / CHAT"),
                                subsectionWithPath("changes")
                                        .description("monthlySaving, targetPeriodMonths, savingsRatioPct, "
                                                + "savingsRatePct 중 바꾼 것만"),
                                fieldWithPath("expectedRevision").description("확인창을 열 때의 revision"))
                        .responseFields(VIEW_FIELDS).build())));
    }

    @Test
    void undoPlanDoc() throws Exception {
        savePlan();
        mockMvc.perform(post("/api/plan/apply").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"CHAT\",\"changes\":{\"monthlySaving\":1500000},\"expectedRevision\":1}"));
        mockMvc.perform(post("/api/plan/undo").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk())
                .andDo(document("plan-undo", resource(ResourceSnippetParameters.builder()
                        .tag("Plan").summary("직전 계획으로 되돌리기")
                        .description("한 단계까지만. 되돌릴 계획이 없거나 revision이 다르면 409.")
                        .requestFields(fieldWithPath("expectedRevision").description("현재 revision"))
                        .responseFields(VIEW_FIELDS).build())));
    }

    @Test
    void inputRulesDoc() throws Exception {
        mockMvc.perform(get("/api/input-rules").cookie(cookie))
                .andExpect(status().isOk())
                .andDo(document("input-rules", resource(ResourceSnippetParameters.builder()
                        .tag("Plan").summary("입력 규칙")
                        .description("항목별 필수·범위·단위, 목표 기본값, 기간 프리셋, 기준 비중. 금액은 원.")
                        .responseFields(
                                subsectionWithPath("fields").description("항목명(camelCase) → "
                                        + "{required, min, max, unit, scale?, step?}"),
                                subsectionWithPath("goalTypes").description("[{code, defaultAmount?, "
                                        + "defaultPeriodMonths?}] 화면 순서"),
                                fieldWithPath("periodPresets").description("기간 프리셋(개월)"),
                                fieldWithPath("baseSavingsRatioPct").description("기준 적금 비중(%)"))
                        .build())));
    }

    private static List<FieldDescriptor> concat(List<FieldDescriptor> first, List<FieldDescriptor> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }
}
