package com.moeun.backend.calc;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.calc.application.CalcEngine;
import com.moeun.backend.calc.application.ConditionResolver;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.PlanSaveService;

import jakarta.servlet.http.Cookie;

/**
 * 결과 계산이 실패하면 입력 저장도 롤백된다(계약 C12a-5-2). 저장을 먼저 커밋하면 계산할 수 없는 계획이 남아
 * P-4 500 → 재저장 409 루프에 갇히기 때문이다.
 */
@SpringBootTest(properties = {"moeun.demo.accounts=demo01:pw-123456",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PlanResultRollbackTest {

    private static final String REQUEST = "{\"expectedRevision\":%s,\"goalType\":\"HOUSE\",\"goalCustomName\":null,"
            + "\"goalAmount\":20000000,\"targetPeriodMonths\":36,\"heldAssets\":5000000,"
            + "\"monthlySavingAmount\":300000,\"savingsRateInputPct\":null}";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlanSaveService saveService;

    @MockitoSpyBean
    CalcEngine calcEngine;

    Cookie cookie;
    long memberId;

    @BeforeEach
    void login() throws Exception {
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        cookie = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);
        memberId = jdbc.sql("SELECT id FROM member WHERE login_id = 'demo01'").query(Long.class).single();
        doThrow(new IllegalStateException("test failure")).when(calcEngine)
                .calculate(any(ConditionResolver.ResolvedConditions.class), any(), any());
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    @DisplayName("첫 저장에서 계산이 실패하면 500 INTERNAL_ERROR이고 plan_version·plan 행이 생기지 않는다")
    void rollsBackFirstSaveWhenCalculationFails() throws Exception {
        mockMvc.perform(put("/api/plan").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST.formatted("null")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(count("plan_version")).isZero();
        assertThat(count("plan")).isZero();
    }

    @Test
    @DisplayName("입력 수정에서 계산이 실패하면 plan_version·plan(revision 포함)이 그대로다")
    void rollsBackEditWhenCalculationFails() throws Exception {
        saveService.save(memberId, valid().build(), null);
        // 저장이 발행한 PlanChanged 리스너가 끝나야 다음 테스트의 정리와 겹치지 않는다
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);

        mockMvc.perform(put("/api/plan").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST.formatted("1")))
                .andExpect(status().isInternalServerError());

        assertThat(count("plan_version")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT revision FROM plan").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("현재 계획 조회(GET)에서 계산이 실패하면 500 INTERNAL_ERROR이고 errors는 []다(#31)")
    void returnsInternalErrorWhenQueryCalculationFails() throws Exception {
        saveService.save(memberId, valid().build(), null);
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);

        mockMvc.perform(get("/api/plan").cookie(cookie))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }
}
