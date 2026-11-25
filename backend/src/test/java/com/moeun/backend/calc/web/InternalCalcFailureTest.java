package com.moeun.backend.calc.web;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.calc.application.ResultDisplayBuilder;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.PlanSaveService;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 예상하지 못한 예외는 /internal/calc 컨트롤러 안에서만 500 CALC_FAILED로 바뀌고 calc_run이 롤백된다(계약 C3-2, X-15).
 * 같은 종류의 실패가 다른 경로(PUT /api/plan)에서 나면 공통 처리기의 500 INTERNAL_ERROR다.
 */
@SpringBootTest(properties = {"moeun.ai-server.internal-api-key=test-internal-key",
    "moeun.demo.accounts=demo01:pw-123456",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class InternalCalcFailureTest {

    private static final String INTERNAL_FAILURE = "internal failure detail";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlanSaveService planSaveService;

    @MockitoSpyBean
    ResultDisplayBuilder displayBuilder;

    long memberId;

    @BeforeEach
    void createPlan() {
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        memberId = jdbc.sql("SELECT id FROM member WHERE login_id = 'demo01'").query(Long.class).single();
        planSaveService.save(memberId, valid().goalAmount(20_000_000L).heldAssets(5_000_000L)
                .monthlySavingAmount(300_000L).build(), null);
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }

    @Test
    @DisplayName("기록 뒤 display에서 예외가 나면 500 CALC_FAILED, detail에 내부 메시지가 없고 calc_run(CHAT)이 롤백된다")
    void returnsCalcFailedAndRollsBack() throws Exception {
        // 비교는 목표 금액 포함 여부를 넘기는 6인자 build를 쓴다(T-12)
        doThrow(new IllegalStateException(INTERNAL_FAILURE)).when(displayBuilder)
                .build(any(), any(), eq("changed."), any(), anyBoolean(), anyBoolean());

        String body = mockMvc.perform(post("/internal/calc").header(InternalCalcApiTest.KEY_HEADER,
                                InternalCalcApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":" + memberId
                                + ",\"conditionChanges\":{\"monthlySavingAmount\":500000}}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("CALC_FAILED"))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(INTERNAL_FAILURE);
        // 계약 부록 A-5의 500 예시와 code·title·status·errors·instance가 같다
        JsonNode actual = JSON.readTree(body);
        JsonNode expected;
        try (InputStream in = new ClassPathResource("contract/internal-calc/a5-calc-failed.json").getInputStream()) {
            expected = JSON.readTree(in);
        }
        for (String key : List.of("code", "title", "status", "errors", "instance")) {
            assertThat(actual.get(key)).as(key).isEqualTo(expected.get(key));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM calc_run WHERE run_type = 'CHAT'").query(Long.class).single())
                .isZero();
    }

    @Test
    @DisplayName("같은 종류의 실패가 PUT /api/plan에서 나면 CALC_FAILED가 아니라 500 INTERNAL_ERROR다")
    void keepsOtherPathsOnCommonErrorCode() throws Exception {
        doThrow(new IllegalStateException(INTERNAL_FAILURE)).when(displayBuilder)
                .build(any(), any(), eq(""), any());
        Cookie cookie = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);

        mockMvc.perform(put("/api/plan").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"goalType\":\"HOUSE\",\"goalCustomName\":null,"
                                + "\"goalAmount\":20000000,\"targetPeriodMonths\":36,\"heldAssets\":5000000,"
                                + "\"monthlySavingAmount\":400000,\"savingsRateInputPct\":null,"
                                + "\"savingsRatioPct\":null}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }
}
