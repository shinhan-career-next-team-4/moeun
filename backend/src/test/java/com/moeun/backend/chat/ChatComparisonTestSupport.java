package com.moeun.backend.chat;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.moeun.backend.calc.application.CompareConditionsUseCase;
import com.moeun.backend.calc.application.CompareConditionsUseCase.ComparisonResult;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.PlanSaveResult;
import com.moeun.backend.plan.PlanSaveService;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-4 비교 결과 조회 테스트 공통 기반(#44). 실제 Postgres·로그인 쿠키·실제 plan/calc 모듈을 쓴다.
 * S&P500은 레포의 테스트용 가짜 121개월을 쓴다.
 */
@SpringBootTest(properties = {"moeun.demo.accounts=demo01:pw-123456,demo02:pw-123456",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
abstract class ChatComparisonTestSupport {

    static final Duration EVENT_WAIT = Duration.ofSeconds(20);

    /** /internal/calc 때의 결과와 그 결과를 저장한 RECALC_RESULT 메시지. */
    record Recalc(long messageId, long calcRunId, ComparisonResult then) {
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    PlanSaveService planSaveService;

    @Autowired
    CompareConditionsUseCase compareConditions;

    Cookie cookie;
    long memberId;
    Cookie otherCookie;
    long otherMemberId;

    @BeforeEach
    void prepareComparison() throws Exception {
        awaitEventsCompleted();
        jdbc.sql("DELETE FROM chat_message").update();
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        cookie = login("demo01");
        memberId = memberId("demo01");
        otherCookie = login("demo02");
        otherMemberId = memberId("demo02");
    }

    Cookie login(String loginId) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);
    }

    long memberId(String loginId) {
        return jdbc.sql("SELECT id FROM member WHERE login_id = ?").param(loginId).query(Long.class).single();
    }

    void awaitEventsCompleted() {
        await().atMost(EVENT_WAIT).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }

    /** 계약 A-1과 같은 계획: 목표 2,000만원, 36개월, 보유 500만원, 금리 비움(기본금리), 비중 5:5. */
    PlanSaveResult savePlan(long member, Long expectedRevision, long monthlySavingAmount) {
        PlanSaveResult saved = planSaveService.save(member, valid().goalAmount(20_000_000L).targetPeriodMonths(36)
                .heldAssets(5_000_000L).monthlySavingAmount(monthlySavingAmount).build(), expectedRevision);
        awaitEventsCompleted();
        return saved;
    }

    /**
     * /internal/calc가 하는 일을 흉내 낸다: 현재 계획에 바꾼 조건을 얹어 비교하고(calc_run(CHAT) 기록), #41이 저장하는
     * 모양대로 RECALC_RESULT 메시지를 COMPLETED로 저장한다.
     */
    Recalc recalc(long member, ConditionOverride override, String conditionChangesJson) {
        ComparisonResult then = compareConditions.compare(member, override);
        long messageId = insertRecalcResult(member, then.calcRunId(), "COMPLETED", conditionChangesJson);
        return new Recalc(messageId, then.calcRunId(), then);
    }

    long insertRecalcResult(long member, long calcRunId, String status, String conditionChangesJson) {
        return insertWithRun(member, "RECALC_RESULT", status, calcRunId, conditionChangesJson);
    }

    long insertWithRun(long member, String type, String status, long calcRunId, String conditionChangesJson) {
        return jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, content, calc_run_id, "
                        + "baseline_scenario, condition_changes) "
                        + "VALUES (?, 'ASSISTANT', ?, ?, 'content', ?, 'AVG', CAST(? AS jsonb)) RETURNING id")
                .params(member, type, status, calcRunId, conditionChangesJson).query(Long.class).single();
    }

    ResultActions comparison(Cookie who, Object messageId) throws Exception {
        return mockMvc.perform(get("/api/chat/messages/{id}/comparison", messageId).cookie(who));
    }
}
