package com.moeun.backend.docs;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.headerWithName;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.PlanSaveService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /internal/calc} 문서와 계약 테스트. 계약 부록 A의 예시(test/resources/contract/internal-calc)와 비교한다.
 * A-1은 금액이 예시 값이라 키·타입(모양)을, 오류 예시는 code·title·errors를 비교한다.
 */
@SpringBootTest(properties = {"moeun.ai-server.internal-api-key=test-internal-key",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@Import(TestcontainersConfig.class)
class InternalCalcDocsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY_HEADER = "X-Internal-Api-Key";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlanSaveService planSaveService;

    long memberId;

    @BeforeEach
    void createPlan() {
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        jdbc.sql("DELETE FROM member WHERE login_id = 'internal-docs'").update();
        memberId = jdbc.sql("INSERT INTO member (login_id, password_hash) VALUES ('internal-docs', 'x') RETURNING id")
                .query(Long.class).single();
    }

    private void savePlan(long heldAssets) {
        planSaveService.save(memberId, valid().goalAmount(20_000_000L).targetPeriodMonths(36).heldAssets(heldAssets)
                .monthlySavingAmount(300_000L).build(), null);
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }

    private static JsonNode readContract(String name) {
        try (InputStream in = new ClassPathResource("contract/internal-calc/" + name + ".json").getInputStream()) {
            return JSON.readTree(in);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** 계약 요청 예시의 memberId만 이 테스트 회원으로 바꾼다. */
    private String contractRequest(String name) {
        JsonNode request = readContract(name);
        return request.toString().replaceFirst("\"memberId\":7", "\"memberId\":" + memberId);
    }

    private ResultActions postCalc(String body, String key) throws Exception {
        return mockMvc.perform(post("/internal/calc").header(KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static JsonNode read(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    /** 두 JSON의 키 경로와 값 종류(숫자·문자열·불리언·null·객체·배열)가 같은지. 배열은 첫 원소로 본다. */
    private static List<String> shapeOf(JsonNode node, String path) {
        List<String> shape = new ArrayList<>();
        if (node.isObject()) {
            node.properties().forEach(entry -> shape.addAll(shapeOf(entry.getValue(), path + "." + entry.getKey())));
        } else if (node.isArray()) {
            shape.add(path + "[]");
            if (!node.isEmpty()) {
                shape.addAll(shapeOf(node.get(0), path + "[0]"));
            }
        } else {
            shape.add(path + ":" + (node.isNull() ? "null-or-value" : node.isNumber() ? "number" : node.getNodeType()));
        }
        return shape;
    }

    /**
     * null일 수도 값일 수도 있는 자리는 종류를 맞추지 않는다. goalReachMonth(달성 여부에 따름)와 annualReturnPct
     * (부트스트랩은 연 수익률이 하나로 정해지지 않아 null로 내기로 함, #27 결정. 계약상 null 허용)가 그렇다.
     */
    private static List<String> shapeWithoutNullDetail(JsonNode node) {
        return shapeOf(node, "$").stream()
                .map(entry -> isNullable(entry) ? entry.substring(0, entry.lastIndexOf(':')) : entry)
                .toList();
    }

    private static boolean isNullable(String entry) {
        return entry.endsWith(":null-or-value") || entry.contains("goalReachMonth:")
                || entry.contains("annualReturnPct:");
    }

    @Test
    @DisplayName("A-1 성공 응답과 키·타입이 같다(display 키 50개 포함)")
    void matchesSuccessExampleShape() throws Exception {
        savePlan(5_000_000L);

        JsonNode actual = read(postCalc(contractRequest("a1-request"), "test-internal-key")
                .andExpect(status().isOk())
                .andDo(document("internal-calc", resource(ResourceSnippetParameters.builder()
                        .tag("calc").summary("대화 조건 변경 비교 계산(ai-server 전용)")
                        .description("현재 계획에 conditionChanges만 얹어 current·changed·diff·display를 돌려주고 "
                                + "calc_run(CHAT)을 기록한다. 이전 변경은 누적하지 않는다. 오류는 계약 C3 코드표를 따른다.")
                        .requestHeaders(headerWithName(KEY_HEADER).description("Spring ↔ ai-server 공유 비밀"))
                        .requestFields(
                                fieldWithPath("memberId").description("계산할 회원(정수)"),
                                subsectionWithPath("conditionChanges")
                                        .description("monthlySavingAmount, savingsRatioPct, savingsRateInputPct, "
                                                + "targetPeriodMonths 중 바꾼 것만"),
                                fieldWithPath("baselineScenario").description("무시한다(계획의 기준 시나리오를 씀)")
                                        .optional())
                        .responseFields(
                                fieldWithPath("calcRunId").description("이번 계산의 calc_run(CHAT) id"),
                                subsectionWithPath("conditions").description("계산에 실제 쓴 4개 조건(current·changed)"),
                                subsectionWithPath("current").description("현재 계획 결과(monthlyPoints 없음)"),
                                subsectionWithPath("changed").description("바꾼 조건 결과(monthlyPoints 없음)"),
                                subsectionWithPath("diff").description("changed − current, 양쪽에 있는 시나리오만"),
                                subsectionWithPath("display").description("표시 문자열 맵(C2-6)"))
                        .build()))));
        JsonNode expected = readContract("a1-response");

        assertThat(shapeWithoutNullDetail(actual))
                .containsExactlyInAnyOrderElementsOf(shapeWithoutNullDetail(expected));
        List<String> actualDisplayKeys = new ArrayList<>(actual.get("display").propertyNames());
        assertThat(actualDisplayKeys).containsExactlyInAnyOrderElementsOf(expected.get("display").propertyNames());
    }

    @Test
    @DisplayName("A-2·A-3·A-4 오류 예시와 code·title·detail·errors가 같다")
    void matchesValidationErrorExamples() throws Exception {
        savePlan(5_000_000L);

        for (String example : List.of("a2", "a3", "a4")) {
            JsonNode actual = read(postCalc(contractRequest(example + "-request"), "test-internal-key")
                    .andExpect(status().isUnprocessableEntity()));
            JsonNode expected = readContract(example + "-response");
            assertThat(actual.get("code")).as(example).isEqualTo(expected.get("code"));
            assertThat(actual.get("title")).as(example).isEqualTo(expected.get("title"));
            assertThat(actual.get("detail")).as(example).isEqualTo(expected.get("detail"));
            assertThat(actual.get("errors")).as(example).isEqualTo(expected.get("errors"));
        }
    }

    @Test
    @DisplayName("A-5 오류 예시(409 NO_PLAN·ALREADY_EXCEEDED, 400, 401)와 code·title·errors가 같다")
    void matchesOtherErrorExamples() throws Exception {
        String request = "{\"memberId\":" + memberId + ",\"conditionChanges\":{\"monthlySavingAmount\":500000}}";
        assertSameError(read(postCalc(request, "test-internal-key").andExpect(status().isConflict())),
                "a5-no-plan");
        assertSameError(read(postCalc(request, "wrong").andExpect(status().isUnauthorized())),
                "a5-invalid-internal-key");
        assertSameError(read(postCalc("{\"memberId\":7.5,\"conditionChanges\":{}}", "test-internal-key")
                .andExpect(status().isBadRequest())), "a5-malformed-request");
        savePlan(25_000_000L);
        assertSameError(read(postCalc(request, "test-internal-key").andExpect(status().isConflict())),
                "a5-already-exceeded");
    }

    private static void assertSameError(JsonNode actual, String example) {
        JsonNode expected = readContract(example);
        assertThat(actual.get("code")).as(example).isEqualTo(expected.get("code"));
        assertThat(actual.get("title")).as(example).isEqualTo(expected.get("title"));
        assertThat(actual.get("status")).as(example).isEqualTo(expected.get("status"));
        assertThat(actual.get("errors")).as(example).isEqualTo(expected.get("errors"));
        // 401은 필터가 써서 instance가 없고(계약 A-5 주), 나머지는 /internal/calc다
        assertThat(actual.get("instance")).as(example).isEqualTo(expected.get("instance"));
    }
}
