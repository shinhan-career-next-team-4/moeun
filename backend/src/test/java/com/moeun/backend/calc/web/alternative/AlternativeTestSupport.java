package com.moeun.backend.calc.web.alternative;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.PlanSaveService;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대안 값 산출 API 테스트 공통 기반(#66). 실제 Postgres·로그인 쿠키·실제 plan/calc 모듈을 쓴다.
 * S&P500은 레포의 테스트용 가짜 121개월을 쓴다.
 */
@SpringBootTest(properties = {"moeun.demo.accounts=demo01:pw-123456",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
abstract class AlternativeTestSupport {

    /** 계획의 목표 금액·기간·보유 자산. 월 저축액이 작으면 미달, 크면 달성하는 계획이다. */
    static final long GOAL = 20_000_000L;
    static final int PERIOD = 36;
    static final long HELD = 5_000_000L;
    static final long MISSING_MONTHLY = 300_000L;
    static final long ACHIEVING_MONTHLY = 1_000_000L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    PlanSaveService planSaveService;

    Cookie cookie;
    long memberId;

    @BeforeEach
    void prepareAlternative() throws Exception {
        awaitEventsCompleted();
        jdbc.sql("DELETE FROM chat_message").update();
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        cookie = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);
        memberId = jdbc.sql("SELECT id FROM member WHERE login_id = 'demo01'").query(Long.class).single();
    }

    void awaitEventsCompleted() {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }

    /** 저장된 계획과 계산 기록을 지운다(같은 테스트 안에서 계획을 처음부터 다시 만들 때). */
    void resetPlans() {
        awaitEventsCompleted();
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
    }

    /** 월 저축액은 미달 계획 그대로 두고 기간만 정한 입력. */
    com.moeun.backend.plan.PlanInput validPlanWithPeriod(int months) {
        return valid().goalAmount(GOAL).targetPeriodMonths(months).heldAssets(HELD)
                .monthlySavingAmount(MISSING_MONTHLY).build();
    }

    void savePlan(long monthlySavingAmount) {
        savePlan(monthlySavingAmount, HELD);
    }

    void savePlan(long monthlySavingAmount, long heldAssets) {
        savePlan(GOAL, monthlySavingAmount, heldAssets);
    }

    void savePlan(long goalAmount, long monthlySavingAmount, long heldAssets) {
        planSaveService.save(memberId, valid().goalAmount(goalAmount).targetPeriodMonths(PERIOD).heldAssets(heldAssets)
                .monthlySavingAmount(monthlySavingAmount).build(), null);
        awaitEventsCompleted();
    }

    ResultActions requestAlternatives(Cookie who, String body) throws Exception {
        return mockMvc.perform(post("/api/alternatives").cookie(who).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    ResultActions requestAlternatives(String body) throws Exception {
        return requestAlternatives(cookie, body);
    }

    JsonNode read(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
