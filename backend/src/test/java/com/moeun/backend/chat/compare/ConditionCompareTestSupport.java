package com.moeun.backend.chat.compare;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MvcResult;

import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.PlanSaveService;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 조건 변경 비교 {@code POST /api/compare}(#64) 테스트 공통 기반. 실제 Postgres·로그인 쿠키·plan/calc 모듈을 쓴다. */
@SpringBootTest(properties = {"moeun.demo.accounts=demo01:pw-123456,demo02:pw-123456",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
abstract class ConditionCompareTestSupport {

    private static final Duration EVENT_WAIT = Duration.ofSeconds(20);

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
    void prepare() throws Exception {
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
        await().atMost(EVENT_WAIT).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }

    /** 목표 2,000만원, 36개월, 보유 500만원, 금리 비움(기본금리), 비중 5:5인 계획. */
    void savePlan(long monthlySavingAmount) {
        planSaveService.save(memberId, valid().goalAmount(20_000_000L).targetPeriodMonths(36)
                .heldAssets(5_000_000L).monthlySavingAmount(monthlySavingAmount).build(), null);
        awaitEventsCompleted();
    }

    MvcResult compare(String body) throws Exception {
        return mockMvc.perform(post("/api/compare").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    JsonNode ok(String conditionChanges) throws Exception {
        MvcResult result = compare("{\"conditionChanges\":" + conditionChanges + "}");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    JsonNode problem(String body, int expectedStatus) throws Exception {
        MvcResult result = compare(body);
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return json.readTree(result.getResponse().getContentAsString());
    }

    long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
