package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moeun.backend.auth.application.JwtProperties;
import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.calc.CalcResultView;
import com.moeun.backend.calc.ConditionsView;
import com.moeun.backend.calc.PlanContextView;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.chat.application.PlanContextSnapshot;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;

/**
 * F-3 설명 요청 POST /api/chat/explanations (#43, 계약 H-4·C12b-3a·C12b-11-3·C1a-10 G-9b·G-15a).
 * 소유권·PLAN 여부·기준 시나리오는 실제 calc.PlanContextProvider.findPlanRun이 DB에서 판단한다(calc_run + plan 행을 넣는다).
 * 리스너가 쓰는 planContext 포트만 스텁이다.
 */
class ChatExplanationApiTest extends ChatIntegrationTestSupport {

    private static final Set<String> ACCOUNT_KEYS = Set.of("memberid", "member_id", "member", "loginid", "login_id",
            "password", "accountid");

    private static final String EXPLANATIONS = "/api/chat/explanations";

    /** 2⁵³+1. double로 읽으면 9007199254740992로 반올림되는 가장 작은 양의 정수. */
    private static final long BEYOND_DOUBLE = 9_007_199_254_740_993L;

    @Autowired
    JwtProperties jwtProperties;

    @BeforeEach
    void echoPlanContext() {
        // 리스너가 ai-server(잡아 두는 스텁)까지 가도록 기본으로 컨텍스트를 돌려준다. 비워 두면 리스너가 바로 FAILED로 바꾼다
        given(planContextPort.findForCalcRun(anyLong(), anyLong(), any())).willAnswer(invocation -> {
            long calcRunId = invocation.getArgument(1);
            Scenario baseline = invocation.getArgument(2);
            return Optional.of(new PlanContextSnapshot(baseline, contextOf(calcRunId, baseline, "CALCULATED")));
        });
    }

    // ---------- 고정 데이터·호출 ----------

    /** 그 회원의 PLAN 기록 하나를 만들고 현재 계획 행을 그 버전·기준 시나리오로 둔다. */
    private long planRunOf(long member, String baseline) {
        long calcRun = insertCalcRun(member, "PLAN");
        setCurrentPlan(member, calcRun, baseline);
        return calcRun;
    }

    private void setCurrentPlan(long member, long calcRun, String baseline) {
        long planVersion = jdbc.sql("SELECT plan_version_id FROM calc_run WHERE id = ?").param(calcRun)
                .query(Long.class).single();
        jdbc.sql("DELETE FROM plan WHERE member_id = ?").param(member).update();
        jdbc.sql("INSERT INTO plan (member_id, current_version_id, baseline_scenario) VALUES (?, ?, ?)")
                .params(member, planVersion, baseline).update();
    }

    private ResultActions explain(Cookie who, String body) throws Exception {
        return mockMvc.perform(post("/api/chat/explanations").cookie(who)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions explainRun(Cookie who, long calcRunId) throws Exception {
        return explain(who, "{\"calcRunId\":" + calcRunId + "}");
    }

    private static JsonNode contextOf(long calcRunId, Scenario baseline, String status) {
        return JSON.readTree("""
                {"calcRunId":%d,"conditions":{"monthlySavingAmount":1000000,"targetPeriodMonths":36},
                 "result":{"status":"%s","baselineScenario":"%s","scenarios":[],
                  "assumptions":{"isDefaultSavingsRate":true,"scenarioMethod":null,"excludedItems":null}},
                 "display":{"goalAmount":"1억원"}}""".formatted(calcRunId, status, baseline.name()));
    }

    private static void assertEmptyObject(MvcResult result) throws Exception {
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.isObject()).as("202 본문은 JSON 객체").isTrue();
        assertThat(body.size()).as("202 본문은 빈 객체 {}").isZero();
        assertThat(result.getResponse().getContentType()).isNotNull();
        assertThat(MediaType.parseMediaType(result.getResponse().getContentType())
                .isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
    }

    private List<Map<String, Object>> explanationRowsOf(long member) {
        return jdbc.sql("SELECT id, role, message_type, status, content, calc_run_id, baseline_scenario "
                        + "FROM chat_message WHERE member_id = ? AND calc_run_id IS NOT NULL ORDER BY id")
                .param(member).query().listOfRows();
    }

    private long pendingExplanationId(long member, long calcRun) {
        return jdbc.sql("SELECT id FROM chat_message WHERE member_id = ? AND calc_run_id = ? "
                        + "AND message_type = 'EXPLANATION' AND status = 'PENDING' ORDER BY id DESC LIMIT 1")
                .params(member, calcRun).query(Long.class).single();
    }

    /** 그 기록을 가리키는 가장 최근 행. 리스너가 이미 FAILED로 바꿨을 수 있어 상태를 보지 않는다. */
    private long latestRowIdFor(long member, long calcRun) {
        return jdbc.sql("SELECT id FROM chat_message WHERE member_id = ? AND calc_run_id = ? ORDER BY id DESC LIMIT 1")
                .params(member, calcRun).query(Long.class).single();
    }

    private static String explanationReply(String content, long calcRunId) {
        return JSON.writeValueAsString(Map.of("messageType", "EXPLANATION", "content", content,
                "baselineScenario", "AVG", "calcRunId", calcRunId, "citationChunkIds", List.of()));
    }

    private long allRows() {
        return jdbc.sql("SELECT count(*) FROM chat_message").query(Long.class).single();
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new HashSet<>();
        collectKeys(node, keys);
        return keys;
    }

    private static void collectKeys(JsonNode node, Set<String> keys) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                keys.add(name.toLowerCase(Locale.ROOT));
                collectKeys(node.get(name), keys);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                collectKeys(element, keys);
            }
        }
    }

    // ---------- AC-1 ----------

    @Test
    void explanationReturns202EmptyObjectAndSavesPendingExplanation() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("assistantMessageId");
        List<Map<String, Object>> rows = rowsOf(memberId);
        assertThat(rows).hasSize(1);
        Map<String, Object> saved = row((Long) rows.get(0).get("id"));
        assertThat(saved.get("member_id")).isEqualTo(memberId);
        assertThat(saved.get("role")).isEqualTo("ASSISTANT");
        assertThat(saved.get("message_type")).isEqualTo("EXPLANATION");
        assertThat(saved.get("status")).isEqualTo("PENDING");
        assertThat(saved.get("content")).isNull();
        assertThat(saved.get("calc_run_id")).isEqualTo(calcRun);
        assertThat(saved.get("baseline_scenario")).isEqualTo("AVG");
    }

    @Test
    void explanationPublishesAnswerRequestForTheSavedRow() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");

        explainRun(cookie, calcRun).andExpect(status().isAccepted());

        long explanationId = pendingExplanationId(memberId, calcRun);
        JsonNode request = awaitAiRequests(1).get(0);
        assertThat(request.get("messageId").asLong()).isEqualTo(explanationId);
        assertThat(answerEvents()).isEqualTo(1);
    }

    /** 유효한 calcRunId와 함께 온 retryOfMessageId는 무시하고 평소대로 처리한다(결정 B1 A, FR-AI-05는 범위 밖). */
    @Test
    void retryOfMessageIdWithValidCalcRunIdIsIgnored() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        long previous = insertExplanation(memberId, "COMPLETED", "앞 설명", calcRun, "AVG", Instant.now());

        MvcResult result = explain(cookie, "{\"calcRunId\":" + calcRun + ",\"retryOfMessageId\":" + previous + "}")
                .andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(countRows(memberId)).isEqualTo(2);
        Map<String, Object> kept = row(previous);
        assertThat(kept.get("status")).isEqualTo("COMPLETED");
        assertThat(kept.get("message_type")).isEqualTo("EXPLANATION");
        assertThat(kept.get("content")).isEqualTo("앞 설명");
        long fresh = pendingExplanationId(memberId, calcRun);
        assertThat(fresh).isNotEqualTo(previous);
        Map<String, Object> saved = row(fresh);
        assertThat(saved.get("role")).isEqualTo("ASSISTANT");
        assertThat(saved.get("baseline_scenario")).isEqualTo("AVG");
        // 받지도 저장하지도 않는다: 새 대기 행의 retry_of_message_id는 비어 있다
        assertThat(jdbc.sql("SELECT retry_of_message_id FROM chat_message WHERE id = ?").param(fresh)
                .query().singleRow().get("retry_of_message_id")).isNull();
        assertThat(awaitAiRequests(1).get(0).get("messageId").asLong()).isEqualTo(fresh);
        assertThat(answerEvents()).isEqualTo(1);
    }

    /**
     * retryOfMessageId는 받지 않으므로 값의 형식도 보지 않는다. 정수가 아닌 값·long 범위를 넘는 값이 와도
     * 유효한 calcRunId면 202 {}이고 새 대기 행의 retry_of_message_id는 비어 있다(결정 B1 A).
     * ai-server 응답을 잡아 두므로(기본) 각 행의 초기 역할·종류·상태를 그대로 볼 수 있고, 각 이벤트가 나간 EXPLAIN 요청의
     * messageId로 그 행을 가리키는지 확인한다.
     */
    @Test
    void retryOfMessageIdOfAnyShapeIsIgnored() throws Exception {
        List<String> values = List.of("{}", "\"abc\"", "[1]", "true", "1.5", "null", "99999999999999999999",
                "9".repeat(1001));
        List<Long> runs = new ArrayList<>();
        for (String value : values) {
            long calcRun = planRunOf(memberId, "AVG");
            runs.add(calcRun);

            MvcResult result = explain(cookie, "{\"calcRunId\":" + calcRun + ",\"retryOfMessageId\":" + value + "}")
                    .andExpect(status().isAccepted()).andReturn();

            assertEmptyObject(result);
        }

        assertThat(countRows(memberId)).isEqualTo(values.size());
        Set<Long> savedIds = new HashSet<>();
        for (long calcRun : runs) {
            long latest = latestRowIdFor(memberId, calcRun);
            savedIds.add(latest);
            Map<String, Object> saved = row(latest);
            assertThat(saved.get("role")).isEqualTo("ASSISTANT");
            assertThat(saved.get("message_type")).isEqualTo("EXPLANATION");
            assertThat(saved.get("status")).isEqualTo("PENDING");
            assertThat(saved.get("content")).isNull();
            assertThat(saved.get("calc_run_id")).isEqualTo(calcRun);
            assertThat(jdbc.sql("SELECT retry_of_message_id FROM chat_message WHERE id = ?").param(latest)
                    .query().singleRow().get("retry_of_message_id")).isNull();
        }
        List<JsonNode> requests = awaitAiRequests(values.size());
        assertThat(requests).hasSize(values.size());
        Set<Long> requestedIds = new HashSet<>();
        for (JsonNode request : requests) {
            assertThat(request.get("mode").asString()).isEqualTo("EXPLAIN");
            requestedIds.add(request.get("messageId").asLong());
        }
        assertThat(requestedIds).as("이벤트마다 새 설명 행 하나를 가리킨다").isEqualTo(savedIds);
        assertThat(answerEvents()).isEqualTo(values.size());
    }

    /**
     * long으로 읽을 수 있지만 가리키는 메시지가 없는 retryOfMessageId도 찾아보지 않고 무시한다(결정 B1 A).
     * 그 id로 메시지를 찾아 없으면 404를 내는 구현은 여기서 실패한다. chat_message는 매 테스트 비우므로 아래 id는 모두 없는 행이다.
     */
    @Test
    void retryOfMessageIdPointingToNoMessageIsIgnored() throws Exception {
        List<String> values = List.of("0", "-1", "2147483648", String.valueOf(Long.MAX_VALUE));
        List<Long> runs = new ArrayList<>();
        for (String value : values) {
            long calcRun = planRunOf(memberId, "AVG");
            runs.add(calcRun);

            MvcResult result = explain(cookie, "{\"calcRunId\":" + calcRun + ",\"retryOfMessageId\":" + value + "}")
                    .andExpect(status().isAccepted()).andReturn();

            assertEmptyObject(result);
        }

        assertThat(countRows(memberId)).isEqualTo(values.size());
        Set<Long> savedIds = new HashSet<>();
        for (long calcRun : runs) {
            long latest = latestRowIdFor(memberId, calcRun);
            savedIds.add(latest);
            Map<String, Object> saved = row(latest);
            assertThat(saved.get("role")).isEqualTo("ASSISTANT");
            assertThat(saved.get("message_type")).isEqualTo("EXPLANATION");
            assertThat(saved.get("status")).isEqualTo("PENDING");
            assertThat(saved.get("content")).isNull();
            assertThat(saved.get("calc_run_id")).isEqualTo(calcRun);
            assertThat(jdbc.sql("SELECT retry_of_message_id FROM chat_message WHERE id = ?").param(latest)
                    .query().singleRow().get("retry_of_message_id")).isNull();
        }
        List<JsonNode> requests = awaitAiRequests(values.size());
        assertThat(requests).hasSize(values.size());
        Set<Long> requestedIds = new HashSet<>();
        for (JsonNode request : requests) {
            assertThat(request.get("mode").asString()).isEqualTo("EXPLAIN");
            requestedIds.add(request.get("messageId").asLong());
        }
        assertThat(requestedIds).as("이벤트마다 새 설명 행 하나를 가리킨다").isEqualTo(savedIds);
        assertThat(answerEvents()).isEqualTo(values.size());
    }

    /**
     * calcRunId는 long 전체 범위다. int 범위를 넘는 내 PLAN 기록 id도 그대로 조회·저장·전송한다.
     * 중간에 int로 좁히면 잘린 값(음수)으로 조회해 404가 되므로 여기서 실패한다.
     */
    @Test
    void calcRunIdBeyondIntRangeIsSavedAndSentAsExactLong() throws Exception {
        long small = insertCalcRun(memberId, "PLAN");
        long big = jdbc.sql("SELECT GREATEST(2147483648, COALESCE(max(id), 0) + 1) FROM calc_run")
                .query(Long.class).single();
        jdbc.sql("UPDATE calc_run SET id = ? WHERE id = ?").params(big, small).update();
        assertThat(big).isGreaterThan(Integer.MAX_VALUE);
        setCurrentPlan(memberId, big, "AVG");

        MvcResult result = explainRun(cookie, big).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(countRows(memberId)).isEqualTo(1);
        long saved = pendingExplanationId(memberId, big);
        assertThat(row(saved).get("calc_run_id")).isEqualTo(big);
        JsonNode request = awaitAiRequests(1).get(0);
        assertThat(request.get("mode").asString()).isEqualTo("EXPLAIN");
        assertThat(request.get("messageId").asLong()).isEqualTo(saved);
        JsonNode sentRun = request.get("calcRunId");
        assertThat(sentRun.isIntegralNumber()).as("calcRunId는 JSON 정수").isTrue();
        assertThat(sentRun.asLong()).isEqualTo(big);
        assertThat(sentRun.toString()).isEqualTo(Long.toString(big));
        verify(planContextPort).findForCalcRun(memberId, big, Scenario.AVG);
        assertThat(answerEvents()).isEqualTo(1);
    }

    /**
     * calcRunId를 double로 읽은 뒤 long으로 바꾸면 2⁵³+1이 2⁵³로 바뀐다. 실제 내 PLAN 기록 id를 2⁵³+1로 두고
     * 저장값·포트 인자·ai-server로 나간 JSON 정수가 모두 그 값과 정확히 같은지 본다.
     */
    @Test
    void calcRunIdAbove2Pow53IsNotRoundedThroughDouble() throws Exception {
        assertThat((long) (double) BEYOND_DOUBLE).as("double을 거치면 값이 바뀌는 id").isNotEqualTo(BEYOND_DOUBLE);
        jdbc.sql("DELETE FROM calc_run WHERE id IN (?, ?)").params(BEYOND_DOUBLE, BEYOND_DOUBLE - 1).update();
        long small = insertCalcRun(memberId, "PLAN");
        jdbc.sql("UPDATE calc_run SET id = ? WHERE id = ?").params(BEYOND_DOUBLE, small).update();
        setCurrentPlan(memberId, BEYOND_DOUBLE, "AVG");

        MvcResult result = explainRun(cookie, BEYOND_DOUBLE).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(countRows(memberId)).isEqualTo(1);
        long saved = pendingExplanationId(memberId, BEYOND_DOUBLE);
        assertThat(row(saved).get("calc_run_id")).isEqualTo(BEYOND_DOUBLE);
        JsonNode request = awaitAiRequests(1).get(0);
        assertThat(request.get("messageId").asLong()).isEqualTo(saved);
        JsonNode sentRun = request.get("calcRunId");
        assertThat(sentRun.isIntegralNumber()).as("calcRunId는 JSON 정수").isTrue();
        assertThat(sentRun.toString()).isEqualTo("9007199254740993");
        verify(planContextPort).findForCalcRun(memberId, BEYOND_DOUBLE, Scenario.AVG);
        verify(planContextPort, never()).findForCalcRun(anyLong(), eq(BEYOND_DOUBLE - 1), any());
        assertThat(answerEvents()).isEqualTo(1);
    }

    /**
     * retryOfMessageId가 다른 회원의 기존 메시지를 가리켜도 찾아보지 않고 무시한다(결정 B1 A). 필드를 뺀 요청과 결과가 같다.
     * 참조가 실제로 있을 때만 소유권을 따져 404를 내는 구현은 여기서 실패한다.
     */
    @Test
    void retryOfMessageIdPointingToOthersMessageIsIgnored() throws Exception {
        long othersRun = planRunOf(otherMemberId, "AVG");
        long othersMessage = insertExplanation(otherMemberId, "COMPLETED", "남의 설명", othersRun, "AVG",
                Instant.now());
        Map<String, Object> othersBefore = row(othersMessage);
        long calcRun = planRunOf(memberId, "AVG");

        MvcResult result = explain(cookie, "{\"calcRunId\":" + calcRun + ",\"retryOfMessageId\":" + othersMessage + "}")
                .andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(countRows(memberId)).isEqualTo(1);
        long fresh = pendingExplanationId(memberId, calcRun);
        Map<String, Object> saved = row(fresh);
        assertThat(saved.get("member_id")).isEqualTo(memberId);
        assertThat(saved.get("role")).isEqualTo("ASSISTANT");
        assertThat(saved.get("message_type")).isEqualTo("EXPLANATION");
        assertThat(saved.get("status")).isEqualTo("PENDING");
        assertThat(saved.get("content")).isNull();
        assertThat(jdbc.sql("SELECT retry_of_message_id FROM chat_message WHERE id = ?").param(fresh)
                .query().singleRow().get("retry_of_message_id")).isNull();
        assertThat(row(othersMessage)).as("참조된 다른 회원의 행은 그대로").isEqualTo(othersBefore);
        assertThat(countRows(otherMemberId)).isEqualTo(1);
        assertThat(awaitAiRequests(1).get(0).get("messageId").asLong()).isEqualTo(fresh);
        assertThat(AI.requests()).hasSize(1);
        assertThat(answerEvents()).isEqualTo(1);
    }

    /**
     * F-3은 사용자 조작이라 토큰을 연장한다(C12b-1 "토큰 연장: 예", C12b-2). 성공 응답에 같은 회원의 새 토큰 쿠키가 실리고
     * 그 만료는 요청에 쓴(5분 전 발급) 토큰보다 뒤다. 들고 온 토큰을 그대로 돌려주거나 빈 쿠키를 내리면 실패한다.
     */
    @Test
    void explanationRenewsTokenCookie() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        String oldToken = tokenIssuedMinutesAgo(5);
        Instant oldExpiry = JwtTokenService.decoder(jwtProperties).decode(oldToken).getExpiresAt();

        MvcResult result = explainRun(new Cookie(JwtTokenService.COOKIE_NAME, oldToken), calcRun)
                .andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertRenewedToken(result, oldExpiry);
    }

    /**
     * 인증이 통과한 F-3 요청은 오류(400·404)로 끝나도 토큰을 연장하고, 오류 본문은 application/problem+json이다
     * (frontend-api.md C12a-2-2 "인증이 통과한 요청의 4xx·5xx에도 연장한다", C12b-2, C12b-9). 500은 ChatExplanationOrderTest가 본다.
     */
    @Test
    void authenticatedErrorResponsesAreProblemJsonAndRenewToken() throws Exception {
        long othersRun = planRunOf(otherMemberId, "AVG");
        String oldToken = tokenIssuedMinutesAgo(5);
        Instant oldExpiry = JwtTokenService.decoder(jwtProperties).decode(oldToken).getExpiresAt();
        Cookie aging = new Cookie(JwtTokenService.COOKIE_NAME, oldToken);

        MvcResult notFound = explainRun(aging, othersRun)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors").isEmpty())
                .andReturn();
        MvcResult malformed = explain(aging, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors").isEmpty())
                .andReturn();

        for (MvcResult result : List.of(notFound, malformed)) {
            assertProblemJson(result);
            assertRenewedToken(result, oldExpiry);
        }
        assertThat(allRows()).isZero();
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    /**
     * 지원하지 않는 메서드는 405 METHOD_NOT_ALLOWED, errors=[], problem+json이다(C12b-9 "405 | METHOD_NOT_ALLOWED | 전부").
     * 공통 처리기(config)가 다른 모듈에 내는 코드는 이 Issue 밖이라 바꾸지 않는다(PlanApiTest 그대로).
     */
    @Test
    void unsupportedMethodOnExplanationsReturns405MethodNotAllowed() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        String body = "{\"calcRunId\":" + calcRun + "}";
        List<MockHttpServletRequestBuilder> requests = List.of(get(EXPLANATIONS), put(EXPLANATIONS),
                patch(EXPLANATIONS), delete(EXPLANATIONS));
        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(405))
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                    .andExpect(jsonPath("$.errors").isArray())
                    .andExpect(jsonPath("$.errors").isEmpty());
        }

        assertThat(allRows()).isZero();
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    /** JSON이 아닌 Content-Type은 415 UNSUPPORTED_MEDIA_TYPE, errors=[], problem+json이다(C12b-9 "415 | 본문 있는 것"). */
    @Test
    void nonJsonContentTypeReturns415UnsupportedMediaType() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");

        mockMvc.perform(post(EXPLANATIONS).cookie(cookie).contentType(MediaType.TEXT_PLAIN)
                        .content("{\"calcRunId\":" + calcRun + "}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors").isEmpty());

        assertThat(allRows()).isZero();
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    /** Content-Type·본문이 모두 없으면 빈 본문 오류(400)이고, 파싱할 수 없는 Content-Type은 415 UNSUPPORTED_MEDIA_TYPE이다. */
    @Test
    void missingContentTypeWithEmptyBodyIs400AndUnparseableContentTypeIs415() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");

        mockMvc.perform(post(EXPLANATIONS).cookie(cookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(post(EXPLANATIONS).cookie(cookie).header("Content-Type", "text")
                        .content("{\"calcRunId\":" + calcRun + "}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.errors").isEmpty());

        assertThat(allRows()).isZero();
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    /**
     * 쿠키 없음·만료 토큰은 401 UNAUTHENTICATED, errors=[]이고 아무것도 바꾸지 않는다(C12b-2, C12b-9).
     * 오래된 대기 행 정리도, 설명 행·이벤트·ai-server 호출도 없다. 인증 필터가 이미 막으므로 회귀 보호다.
     */
    @Test
    void withoutOrWithExpiredTokenReturns401AndChangesNothing() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        long stale = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                Instant.now().minus(Duration.ofMinutes(5)));
        Map<String, Object> before = row(stale);
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(31)), ZoneId.of("UTC"));
        Cookie expired = new Cookie(JwtTokenService.COOKIE_NAME,
                new JwtTokenService(jwtProperties, past).issue(memberId));

        mockMvc.perform(post("/api/chat/explanations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calcRunId\":" + calcRun + "}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.errors").isEmpty());
        explainRun(expired, calcRun)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.errors").isEmpty());

        assertThat(row(stale)).as("401이면 오래된 대기 행도 정리하지 않는다").isEqualTo(before);
        assertThat(countRows(memberId)).isEqualTo(1);
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    /**
     * 서명이 맞지 않는 토큰(다른 키로 서명)도 401 UNAUTHENTICATED, errors=[], problem+json이고 아무것도 바꾸지 않는다
     * (C12b-9 "쿠키 없음·서명 불일치·만료"). 인증 필터가 이미 막으므로 회귀 보호다.
     */
    @Test
    void tamperedSignatureTokenReturns401AndChangesNothing() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        long stale = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                Instant.now().minus(Duration.ofMinutes(5)));
        Map<String, Object> before = row(stale);
        JwtProperties otherKey = new JwtProperties("signature-mismatch-test-key-0123456789abcdef",
                jwtProperties.accessTokenTtl(), jwtProperties.issuer(), false);
        Cookie forged = new Cookie(JwtTokenService.COOKIE_NAME,
                new JwtTokenService(otherKey, Clock.systemUTC()).issue(memberId));

        MvcResult result = explainRun(forged, calcRun)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andReturn();

        assertProblemJson(result);
        assertThat(row(stale)).as("401이면 오래된 대기 행도 정리하지 않는다").isEqualTo(before);
        assertThat(countRows(memberId)).isEqualTo(1);
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    private String tokenIssuedMinutesAgo(long minutes) {
        Clock earlier = Clock.fixed(Instant.now().minus(Duration.ofMinutes(minutes)), ZoneId.of("UTC"));
        return new JwtTokenService(jwtProperties, earlier).issue(memberId);
    }

    /** 응답의 토큰 쿠키가 하나이고, 같은 회원의 유효한 새 JWT이며 만료가 oldExpiry보다 뒤다. */
    private void assertRenewedToken(MvcResult result, Instant oldExpiry) {
        List<String> tokens = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(JwtTokenService.COOKIE_NAME + "="))
                .map(value -> value.substring(JwtTokenService.COOKIE_NAME.length() + 1).split(";", 2)[0])
                .toList();
        assertThat(tokens).as("새 토큰 쿠키 하나").hasSize(1);
        assertThat(tokens.get(0)).as("빈 값(만료 쿠키)이 아니다").isNotEmpty();
        Jwt renewed = JwtTokenService.decoder(jwtProperties).decode(tokens.get(0));
        assertThat(renewed.getSubject()).isEqualTo(String.valueOf(memberId));
        assertThat(renewed.getExpiresAt()).as("만료가 뒤로 연장된다").isAfter(oldExpiry);
    }

    private static void assertProblemJson(MvcResult result) {
        assertThat(result.getResponse().getContentType()).isNotNull();
        assertThat(MediaType.parseMediaType(result.getResponse().getContentType())
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).as("오류 본문은 application/problem+json").isTrue();
    }

    // ---------- AC-2 ----------

    @Test
    void othersPlanRunReturns404NotFound() throws Exception {
        long othersRun = planRunOf(otherMemberId, "AVG");

        explainRun(cookie, othersRun)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errors").isEmpty());

        assertThat(allRows()).isZero();
    }

    // ---------- AC-3 ----------

    @Test
    void planContextSentToAiServerHasNoAccountFields() throws Exception {
        long calcRun = planRunOf(memberId, "GOOD");
        JsonNode context = contextOf(calcRun, Scenario.GOOD, "CALCULATED");
        given(planContextPort.findForCalcRun(memberId, calcRun, Scenario.GOOD))
                .willReturn(Optional.of(new PlanContextSnapshot(Scenario.GOOD, context)));

        explainRun(cookie, calcRun).andExpect(status().isAccepted());
        JsonNode sent = awaitAiRequests(1).get(0).get("planContext");

        assertThat(sent).isEqualTo(context);
        assertThat(keysOf(sent)).doesNotContainAnyElementsOf(ACCOUNT_KEYS);
        assertThat(sent.toString()).doesNotContain("demo01").doesNotContain("demo02");
    }

    /** planContext 타입(calc 공개 API)과 chat 스냅샷에 회원 식별 필드가 없다. 이미 있는 구조를 지키는 회귀 보호. */
    @Test
    void planContextTypesHaveNoAccountFields() {
        List<String> names = new ArrayList<>();
        for (Class<?> type : List.of(PlanContextView.class, ConditionsView.class, CalcResultView.class,
                PlanContextSnapshot.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                names.add(component.getName().toLowerCase(Locale.ROOT));
            }
        }
        assertThat(names).isNotEmpty();
        assertThat(names).noneMatch(name -> name.contains("member") || name.contains("login")
                || name.contains("password"));
    }

    // ---------- AC-4 ----------

    @Test
    void missingForeignAndChatRunsReturnSameIndistinguishable404() throws Exception {
        long myRun = planRunOf(memberId, "AVG");
        long othersRun = planRunOf(otherMemberId, "AVG");
        long myChatRun = insertCalcRun(memberId, "CHAT");
        long missing = Math.max(Math.max(myRun, othersRun), myChatRun) + 1_000_000L;

        List<String> bodies = new ArrayList<>();
        for (long calcRunId : List.of(missing, othersRun, myChatRun, 0L, -1L, Long.MAX_VALUE)) {
            MvcResult result = explainRun(cookie, calcRunId)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.errors").isEmpty())
                    .andReturn();
            bodies.add(result.getResponse().getContentAsString());
        }

        JsonNode first = JSON.readTree(bodies.get(0));
        for (String body : bodies) {
            assertThat(JSON.readTree(body)).as("어느 경우인지 본문으로 구분되지 않는다").isEqualTo(first);
        }
        assertThat(allRows()).isZero();
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    /**
     * 회원은 JWT subject로만 정한다(persistence.md). 본문에 다른 회원의 memberId를 넣어도 소유권 확인·저장은 JWT 회원 기준이다.
     * 알 수 없는 본문 필드는 retryOfMessageId처럼 무시한다(잠정 결정 P-31): 남의 기록이면 일반 404와 같은 본문,
     * 내 기록이면 202 {}와 내 대기 행이다.
     */
    @Test
    void memberIdInBodyDoesNotOverrideJwtMember() throws Exception {
        long othersRun = planRunOf(otherMemberId, "AVG");
        String plain404 = explainRun(otherCookie, Math.addExact(othersRun, 1_000_000L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();

        MvcResult spoofed = explain(cookie, "{\"calcRunId\":" + othersRun + ",\"memberId\":" + otherMemberId + "}")
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(JSON.readTree(spoofed.getResponse().getContentAsString()))
                .as("남의 기록은 본문 memberId가 있어도 일반 404와 구분되지 않는다").isEqualTo(JSON.readTree(plain404));
        assertThat(allRows()).isZero();
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();

        long myRun = planRunOf(memberId, "AVG");
        MvcResult mine = explain(cookie, "{\"calcRunId\":" + myRun + ",\"memberId\":" + otherMemberId + "}")
                .andExpect(status().isAccepted())
                .andReturn();

        assertEmptyObject(mine);
        long saved = pendingExplanationId(memberId, myRun);
        assertThat(row(saved).get("member_id")).isEqualTo(memberId);
        assertThat(countRows(memberId)).isEqualTo(1);
        assertThat(countRows(otherMemberId)).as("본문 memberId의 회원에게 행이 생기지 않는다").isZero();
        assertThat(allRows()).isEqualTo(1);
        assertThat(awaitAiRequests(1).get(0).get("messageId").asLong()).isEqualTo(saved);
        assertThat(answerEvents()).isEqualTo(1);
    }

    /** 소유권 확인이 재사용 판단보다 먼저다. 내 대기 설명 행이 남의 기록·CHAT 기록을 가리켜도 재사용 202가 아니라 404다. */
    @Test
    void pendingExplanationRowDoesNotBypassOwnershipCheck() throws Exception {
        long othersRun = planRunOf(otherMemberId, "AVG");
        long myChatRun = insertCalcRun(memberId, "CHAT");
        long onOthers = insertExplanation(memberId, "PENDING", null, othersRun, "AVG", Instant.now());
        long onChat = insertExplanation(memberId, "PENDING", null, myChatRun, "AVG", Instant.now());
        long missing = Math.max(othersRun, myChatRun) + 1_000_000L;

        List<JsonNode> bodies = new ArrayList<>();
        for (long calcRunId : List.of(missing, othersRun, myChatRun)) {
            String body = explainRun(cookie, calcRunId)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.errors").isEmpty())
                    .andReturn().getResponse().getContentAsString();
            bodies.add(JSON.readTree(body));
        }

        // 대기 행이 있는 두 404도 없는 기록의 404와 본문 전체가 같다(소유권·종류를 드러내지 않는다)
        assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
        assertThat(bodies.get(2)).isEqualTo(bodies.get(0));
        assertThat(countRows(memberId)).isEqualTo(2);
        assertThat(statusOf(onOthers)).isEqualTo("PENDING");
        assertThat(statusOf(onChat)).isEqualTo("PENDING");
        assertThat(countRows(otherMemberId)).isZero();
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    // ---------- AC-5 ----------

    @Test
    void storesBaselineFromFindPlanRunAndExplainKeepsItAfterPlanChanges() throws Exception {
        long calcRun = planRunOf(memberId, "GOOD");

        explainRun(cookie, calcRun).andExpect(status().isAccepted());
        long explanationId = pendingExplanationId(memberId, calcRun);
        assertThat(row(explanationId).get("baseline_scenario")).isEqualTo("GOOD");
        assertThat(awaitAiRequests(1).get(0).get("baselineScenario").asString()).isEqualTo("GOOD");

        // 그 뒤 현재 계획의 기준 시나리오가 바뀌고, 같은 대기 행을 리스너가 다시 처리한다(재기동 재처리와 같음)
        jdbc.sql("UPDATE plan SET baseline_scenario = 'BAD' WHERE member_id = ?").param(memberId).update();
        publishAnswerRequested(explanationId, memberId);
        JsonNode again = awaitAiRequests(2).get(1);

        assertThat(again.get("mode").asString()).isEqualTo("EXPLAIN");
        assertThat(again.get("baselineScenario").asString()).isEqualTo("GOOD");
        assertThat(again.get("planContext").get("result").get("baselineScenario").asString()).isEqualTo("GOOD");
        assertThat(row(explanationId).get("baseline_scenario")).isEqualTo("GOOD");
        verify(planContextPort, never()).findForCalcRun(anyLong(), anyLong(), eq(Scenario.BAD));
    }

    /**
     * 대기 설명이 있는 동안 계획의 기준 시나리오가 바뀌고 같은 calcRunId로 다시 요청하면 그 행을 재사용한다.
     * 재사용 때 findPlanRun이 돌려준 새 값(BAD)으로 저장 값을 덮지 않는다. 그 뒤 리스너가 그 행을 다시 처리해도 GOOD이다.
     */
    @Test
    void reusingPendingExplanationAfterPlanChangeKeepsStoredBaseline() throws Exception {
        long calcRun = planRunOf(memberId, "GOOD");
        explainRun(cookie, calcRun).andExpect(status().isAccepted());
        long explanationId = pendingExplanationId(memberId, calcRun);
        assertThat(awaitAiRequests(1).get(0).get("baselineScenario").asString()).isEqualTo("GOOD");

        jdbc.sql("UPDATE plan SET baseline_scenario = 'BAD' WHERE member_id = ?").param(memberId).update();
        MvcResult again = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(again);
        assertThat(countRows(memberId)).isEqualTo(1);
        Map<String, Object> kept = row(explanationId);
        assertThat(kept.get("status")).isEqualTo("PENDING");
        assertThat(kept.get("message_type")).isEqualTo("EXPLANATION");
        assertThat(kept.get("content")).isNull();
        assertThat(kept.get("calc_run_id")).isEqualTo(calcRun);
        assertThat(kept.get("baseline_scenario")).as("재사용 때 저장된 기준 시나리오를 바꾸지 않는다").isEqualTo("GOOD");
        assertThat(answerEvents()).isEqualTo(1);
        assertThat(AI.requests()).hasSize(1);

        // 같은 대기 행을 리스너가 다시 처리해도(재기동 재처리와 같음) 저장된 GOOD을 쓴다
        publishAnswerRequested(explanationId, memberId);
        JsonNode replay = awaitAiRequests(2).get(1);

        assertThat(replay.get("messageId").asLong()).isEqualTo(explanationId);
        assertThat(replay.get("baselineScenario").asString()).isEqualTo("GOOD");
        assertThat(replay.get("planContext").get("result").get("baselineScenario").asString()).isEqualTo("GOOD");
        assertThat(row(explanationId).get("baseline_scenario")).isEqualTo("GOOD");
        verify(planContextPort, times(2)).findForCalcRun(memberId, calcRun, Scenario.GOOD);
        verify(planContextPort, never()).findForCalcRun(anyLong(), anyLong(), eq(Scenario.BAD));
    }

    @Test
    void storedBaselineFollowsCurrentPlanAtRequestTime() throws Exception {
        long calcRun = planRunOf(memberId, "BAD");

        explainRun(cookie, calcRun).andExpect(status().isAccepted());

        assertThat(row(pendingExplanationId(memberId, calcRun)).get("baseline_scenario")).isEqualTo("BAD");
    }

    /**
     * 현재 계획 버전보다 앞선 내 PLAN 기록도 내 기록이라 202다. 저장 기준 시나리오는 findPlanRun이 돌려준 요청 시점의
     * plan.baseline_scenario이고(C1a-10 v3), EXPLAIN은 요청한 그 기록 id로 나간다.
     */
    @Test
    void olderPlanRunOfMineIsExplainedWithBaselineAtRequestTime() throws Exception {
        long olderRun = planRunOf(memberId, "GOOD");
        long currentRun = planRunOf(memberId, "BAD");
        assertThat(currentRun).isNotEqualTo(olderRun);

        MvcResult result = explainRun(cookie, olderRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(countRows(memberId)).isEqualTo(1);
        long explanationId = pendingExplanationId(memberId, olderRun);
        assertThat(row(explanationId).get("baseline_scenario")).isEqualTo("BAD");
        JsonNode request = awaitAiRequests(1).get(0);
        assertThat(request.get("calcRunId").asLong()).isEqualTo(olderRun);
        assertThat(request.get("baselineScenario").asString()).isEqualTo("BAD");
        verify(planContextPort).findForCalcRun(memberId, olderRun, Scenario.BAD);
        verify(planContextPort, never()).findForCalcRun(anyLong(), eq(currentRun), any());
    }

    /**
     * 설명이 COMPLETED가 된 뒤 계획의 기준 시나리오가 바뀌고 같은 calcRunId로 다시 요청하면, 새 행은 그 요청 시점의
     * findPlanRun 값(BAD)을 저장하고 EXPLAIN도 BAD로 나간다. 앞 완료 행은 GOOD 그대로다.
     * 같은 기록의 이전 행에서 기준 시나리오를 복사하는 구현은 여기서 실패한다.
     */
    @Test
    void newExplanationAfterCompletedOneStoresBaselineAtNewRequest() throws Exception {
        long calcRun = planRunOf(memberId, "GOOD");
        explainRun(cookie, calcRun).andExpect(status().isAccepted());
        long firstId = pendingExplanationId(memberId, calcRun);
        assertThat(awaitAiRequests(1).get(0).get("baselineScenario").asString()).isEqualTo("GOOD");
        AI.release(200, """
                {"messageType":"EXPLANATION","content":"첫 설명","baselineScenario":"GOOD","calcRunId":%d,
                 "conditionChanges":null,"citationChunkIds":[]}""".formatted(calcRun));
        awaitStatus(firstId, "COMPLETED");
        Map<String, Object> firstBefore = row(firstId);
        // 두 번째 EXPLAIN 응답은 잡아 둔다. 새 행을 PENDING 상태로 볼 수 있다
        AI.hold();

        jdbc.sql("UPDATE plan SET baseline_scenario = 'BAD' WHERE member_id = ?").param(memberId).update();
        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(countRows(memberId)).isEqualTo(2);
        long secondId = pendingExplanationId(memberId, calcRun);
        assertThat(secondId).isGreaterThan(firstId);
        Map<String, Object> second = row(secondId);
        assertThat(second.get("message_type")).isEqualTo("EXPLANATION");
        assertThat(second.get("status")).isEqualTo("PENDING");
        assertThat(second.get("calc_run_id")).isEqualTo(calcRun);
        assertThat(second.get("baseline_scenario")).as("새 행은 요청 시점 값").isEqualTo("BAD");
        JsonNode secondRequest = awaitAiRequests(2).get(1);
        assertThat(secondRequest.get("messageId").asLong()).isEqualTo(secondId);
        assertThat(secondRequest.get("baselineScenario").asString()).isEqualTo("BAD");
        assertThat(secondRequest.get("planContext").get("result").get("baselineScenario").asString())
                .isEqualTo("BAD");
        verify(planContextPort, times(1)).findForCalcRun(memberId, calcRun, Scenario.GOOD);
        verify(planContextPort, times(1)).findForCalcRun(memberId, calcRun, Scenario.BAD);
        assertThat(row(firstId)).as("앞 완료 행은 GOOD·내용 그대로").isEqualTo(firstBefore);
        assertThat(row(firstId).get("baseline_scenario")).isEqualTo("GOOD");
        assertThat(answerEvents()).isEqualTo(2);
    }

    // ---------- AC-6 ----------

    @Test
    void explainRequestGoesToAiServerWithStoredBaselineOutsideTransaction() throws Exception {
        long calcRun = planRunOf(memberId, "GOOD");
        JsonNode context = contextOf(calcRun, Scenario.GOOD, "CALCULATED");
        given(planContextPort.findForCalcRun(memberId, calcRun, Scenario.GOOD))
                .willReturn(Optional.of(new PlanContextSnapshot(Scenario.GOOD, context)));

        explainRun(cookie, calcRun).andExpect(status().isAccepted());
        long explanationId = pendingExplanationId(memberId, calcRun);
        JsonNode request = awaitAiRequests(1).get(0);

        // 같은 호출의 HTTP 메서드와 경로: ai-server 계약은 POST /chat 하나다
        assertThat(AI.calls()).hasSize(1);
        assertThat(AI.calls().get(0).method()).isEqualTo("POST");
        assertThat(AI.calls().get(0).path()).isEqualTo("/chat");
        assertThat(request.get("mode").asString()).isEqualTo("EXPLAIN");
        // ai-server ChatRequest의 필수 최상위 필드(member_id: int). 요청을 인증한 회원이다. planContext 안에는 없다(AC-3)
        assertThat(request.has("memberId")).as("EXPLAIN 요청 최상위 memberId").isTrue();
        assertThat(request.get("memberId").isIntegralNumber()).isTrue();
        assertThat(request.get("memberId").asLong()).isEqualTo(memberId);
        assertThat(request.get("messageId").asLong()).isEqualTo(explanationId);
        assertThat(request.get("calcRunId").asLong()).isEqualTo(calcRun);
        assertThat(request.get("baselineScenario").asString()).isEqualTo("GOOD");
        assertThat(request.get("planContext")).isEqualTo(context);
        assertThat(!request.has("text") || request.get("text").isNull()).isTrue();
        verify(planContextPort).findForCalcRun(memberId, calcRun, Scenario.GOOD);
        verify(planContextPort, never()).findForCurrentPlan(anyLong());
        assertThat(aiCallTransactionStates()).isNotEmpty().containsOnly(false);

        AI.release(200, """
                {"messageType":"EXPLANATION","content":"설명","baselineScenario":"GOOD","calcRunId":%d,
                 "conditionChanges":null,"citationChunkIds":[]}""".formatted(calcRun));
        awaitStatus(explanationId, "COMPLETED");
        Map<String, Object> done = row(explanationId);
        assertThat(done.get("message_type")).isEqualTo("EXPLANATION");
        assertThat(done.get("content")).isEqualTo("설명");
    }

    /**
     * ai-server로 가는 요청 전체(헤더·본문)에 사용자 JWT·쿠키를 싣지 않는다. 회원은 최상위 정수 memberId로만 간다
     * (ai-server ChatRequest "사용자 JWT는 넘기지 않고 Spring이 member_id를 담는다", C1a-7).
     * 실패 메시지에 토큰 원문이 남지 않도록 참·거짓으로만 단언한다.
     */
    @Test
    void aiServerRequestCarriesNoUserTokenOrCookie() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        String token = cookie.getValue();

        explainRun(cookie, calcRun).andExpect(status().isAccepted());
        JsonNode request = awaitAiRequests(1).get(0);

        assertThat(AI.calls()).hasSize(1);
        Map<String, List<String>> headers = AI.calls().get(0).headers();
        assertThat(headers.containsKey("cookie")).as("Cookie 헤더 없음").isFalse();
        assertThat(headers.containsKey("authorization")).as("Authorization 헤더 없음").isFalse();
        boolean tokenInHeaders = headers.values().stream().flatMap(List::stream)
                .anyMatch(value -> value.contains(token) || value.contains(JwtTokenService.COOKIE_NAME));
        assertThat(tokenInHeaders).as("헤더에 사용자 JWT·토큰 쿠키 없음").isFalse();
        String body = JSON.writeValueAsString(request);
        assertThat(body.contains(token)).as("본문에 사용자 JWT 없음").isFalse();
        assertThat(body.contains(JwtTokenService.COOKIE_NAME)).as("본문에 토큰 쿠키 이름 없음").isFalse();
        assertThat(request.get("memberId").isIntegralNumber()).isTrue();
        assertThat(request.get("memberId").asLong()).isEqualTo(memberId);
    }

    /**
     * planContext를 만들지 못해도(빈 값 또는 예외) F-3 응답은 이미 202 {}이고, 설명 행은 ai-server 호출 없이
     * FAILED·FAILURE·chat.failed가 된다(FR-AI-04, C1a-10 생성 실패). 결과 화면 요청에는 영향이 없다.
     */
    @Test
    void planContextFailureStillReturns202AndMarksExplanationFailed() throws Exception {
        long emptyRun = planRunOf(memberId, "AVG");
        given(planContextPort.findForCalcRun(memberId, emptyRun, Scenario.AVG)).willReturn(Optional.empty());
        List<Map<String, Object>> beforeFirst = planAndCalcRows();

        MvcResult first = explainRun(cookie, emptyRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(first);
        long emptyId = latestRowIdFor(memberId, emptyRun);
        awaitStatus(emptyId, "FAILED");
        awaitAnswerEventsCompleted();
        // 설명 실패는 결과 화면의 원천(계획 조건·현재 계획·계산 기록)을 바꾸지 않는다(FR-AI-04)
        assertThat(planAndCalcRows()).isEqualTo(beforeFirst);

        long brokenRun = planRunOf(memberId, "GOOD");
        given(planContextPort.findForCalcRun(memberId, brokenRun, Scenario.GOOD))
                .willThrow(new IllegalStateException("기준 데이터 오류"));
        List<Map<String, Object>> beforeSecond = planAndCalcRows();

        MvcResult second = explainRun(cookie, brokenRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(second);
        long brokenId = latestRowIdFor(memberId, brokenRun);
        awaitStatus(brokenId, "FAILED");
        await().atMost(Duration.ofSeconds(10)).until(() -> answerEvents() == 2 && incompleteAnswerEvents() == 0);
        assertThat(planAndCalcRows()).isEqualTo(beforeSecond);

        for (long id : List.of(emptyId, brokenId)) {
            Map<String, Object> failed = row(id);
            assertThat(failed.get("message_type")).isEqualTo("FAILURE");
            assertThat(failed.get("content")).isEqualTo(failedSentence());
        }
        awaitAnswerEventsCompleted();
        assertThat(answerEvents()).isEqualTo(2);
        assertThat(AI.requests()).isEmpty();
        assertThat(countRows(memberId)).isEqualTo(2);
    }

    /** F-3이 202를 돌려준 뒤 EXPLAIN 호출이 ai-server 오류(502)로 끝나면 같은 설명 행이 FAILED·FAILURE·chat.failed가 된다. */
    @Test
    void aiServerErrorAfter202MarksExplanationFailed() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        List<Map<String, Object>> before = planAndCalcRows();
        AI.respond(502, "{\"error\":\"BAD_GATEWAY\"}");

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        long explanationId = latestRowIdFor(memberId, calcRun);
        awaitStatus(explanationId, "FAILED");
        Map<String, Object> failed = row(explanationId);
        assertThat(failed.get("message_type")).isEqualTo("FAILURE");
        assertThat(failed.get("content")).isEqualTo(failedSentence());
        JsonNode request = awaitAiRequests(1).get(0);
        assertThat(request.get("mode").asString()).isEqualTo("EXPLAIN");
        assertThat(request.get("messageId").asLong()).isEqualTo(explanationId);
        awaitAnswerEventsCompleted();
        assertThat(answerEvents()).isEqualTo(1);
        assertThat(AI.requests()).hasSize(1);
        assertThat(countRows(memberId)).isEqualTo(1);
        // 설명 실패는 결과 화면의 원천(계획 조건·현재 계획·계산 기록)을 바꾸지 않는다(FR-AI-04)
        assertThat(planAndCalcRows()).isEqualTo(before);
    }

    /** EXPLAIN에 ai-server가 HTTP 200이지만 오류 본문으로 답해도 실패다. 같은 설명 행이 FAILED·FAILURE·chat.failed가 된다. */
    @Test
    void badOutputBodyWithOkStatusMarksExplanationFailed() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        List<Map<String, Object>> before = planAndCalcRows();
        AI.respond(200, "{\"error\":\"BAD_OUTPUT\"}");

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        long explanationId = latestRowIdFor(memberId, calcRun);
        awaitStatus(explanationId, "FAILED");
        awaitAnswerEventsCompleted();
        Map<String, Object> failed = row(explanationId);
        assertThat(failed.get("message_type")).isEqualTo("FAILURE");
        assertThat(failed.get("content")).isEqualTo(failedSentence());
        JsonNode request = awaitAiRequests(1).get(0);
        assertThat(request.get("mode").asString()).isEqualTo("EXPLAIN");
        assertThat(request.get("messageId").asLong()).isEqualTo(explanationId);
        assertThat(answerEvents()).isEqualTo(1);
        assertThat(countRows(memberId)).isEqualTo(1);
        assertThat(planAndCalcRows()).isEqualTo(before);
    }

    /** EXPLAIN에 ai-server가 HTTP 200이지만 읽을 수 없는(잘린) JSON으로 답해도 실패다. 행을 PENDING으로 남기지 않는다. */
    @Test
    void unparseableOkBodyMarksExplanationFailed() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        List<Map<String, Object>> before = planAndCalcRows();
        AI.respond(200, "{\"messageType\":");

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        long explanationId = latestRowIdFor(memberId, calcRun);
        awaitStatus(explanationId, "FAILED");
        awaitAnswerEventsCompleted();
        Map<String, Object> failed = row(explanationId);
        assertThat(failed.get("message_type")).isEqualTo("FAILURE");
        assertThat(failed.get("content")).isEqualTo(failedSentence());
        JsonNode request = awaitAiRequests(1).get(0);
        assertThat(request.get("mode").asString()).isEqualTo("EXPLAIN");
        assertThat(request.get("messageId").asLong()).isEqualTo(explanationId);
        assertThat(answerEvents()).isEqualTo(1);
        assertThat(AI.requests()).hasSize(1);
        assertThat(countRows(memberId)).isEqualTo(1);
        assertThat(planAndCalcRows()).isEqualTo(before);
    }

    /** 그 회원의 plan_version·plan·calc_run 행 전체 값(ChatAnswerListenerTest와 같은 방식). */
    private List<Map<String, Object>> planAndCalcRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.sql("SELECT * FROM plan_version WHERE member_id = ? ORDER BY id").param(memberId)
                .query().listOfRows());
        rows.addAll(jdbc.sql("SELECT * FROM plan WHERE member_id = ?").param(memberId).query().listOfRows());
        rows.addAll(jdbc.sql("SELECT cr.* FROM calc_run cr JOIN plan_version pv ON pv.id = cr.plan_version_id "
                + "WHERE pv.member_id = ? ORDER BY cr.id").param(memberId).query().listOfRows());
        return rows;
    }

    /**
     * planContext는 F-3 요청 처리 안이 아니라 커밋 뒤 리스너가 만든다. 요청은 별도 작업으로 보내고 포트를 실제로 막아 둔다.
     * 포트가 막힌 채로 F-3이 202 {}로 끝나야 하고(포트 결과를 기다리지 않음), 포트는 요청 스레드가 아닌 곳에서 정확히 1번 불리며,
     * 불리는 순간 대기 행은 새 트랜잭션(독립 커넥션)에서도 보이게(커밋되어) 있다. 포트 대기가 시간 초과되면 테스트 실패다.
     */
    @Test
    void planContextIsBuiltByListenerAfterCommitNotInsideRequest() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean portReturned = new AtomicBoolean(false);
        AtomicBoolean portTimedOut = new AtomicBoolean(false);
        List<Thread> callers = new CopyOnWriteArrayList<>();
        List<String> seenByPort = new CopyOnWriteArrayList<>();
        List<Boolean> portTransactionStates = new CopyOnWriteArrayList<>();
        TransactionTemplate independent = new TransactionTemplate(transactions.getTransactionManager());
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        given(planContextPort.findForCalcRun(memberId, calcRun, Scenario.AVG)).willAnswer(invocation -> {
            callers.add(Thread.currentThread());
            // 리스너는 트랜잭션 없이 포트에 들어온다(planContext 생성은 제공자 자체 트랜잭션, C1a-10). 독립 조회를 열기 전에 본다
            portTransactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
            // 포트를 부른 쪽의 트랜잭션에 합류하지 않도록 새 트랜잭션(다른 커넥션)에서 읽는다. 미커밋 INSERT는 보이지 않는다
            seenByPort.add(independent.execute(status -> jdbc.sql("SELECT status FROM chat_message "
                            + "WHERE member_id = ? AND calc_run_id = ? AND message_type = 'EXPLANATION'")
                    .params(memberId, calcRun).query(String.class).optional().orElse("NONE")));
            entered.countDown();
            if (!proceed.await(WAIT.toSeconds() * 4, TimeUnit.SECONDS)) {
                portTimedOut.set(true);
            }
            portReturned.set(true);
            return Optional.of(new PlanContextSnapshot(Scenario.AVG, contextOf(calcRun, Scenario.AVG, "CALCULATED")));
        });
        ExecutorService requester = Executors.newSingleThreadExecutor();
        try {
            List<Thread> requestThreads = new CopyOnWriteArrayList<>();
            Future<MvcResult> pending = requester.submit(() -> {
                requestThreads.add(Thread.currentThread());
                return explainRun(cookie, calcRun).andReturn();
            });

            assertThat(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("리스너가 포트를 부른다").isTrue();
            assertThat(seenByPort).as("포트가 불릴 때 대기 행은 독립 커넥션에서 이미 보인다(커밋됨)")
                    .containsExactly("PENDING");
            assertThat(portTransactionStates).as("리스너가 포트를 부를 때 열린 트랜잭션이 없다").containsExactly(false);
            // 포트를 막아 둔 채로 요청이 끝나야 한다. 포트 결과를 기다리는 구현이면 여기서 시간 초과로 실패한다
            MvcResult result = pending.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertThat(portReturned).as("요청은 포트가 답하기 전에 끝난다").isFalse();
            assertThat(result.getResponse().getStatus()).isEqualTo(202);
            assertEmptyObject(result);
            assertThat(callers).as("요청 스레드에서 포트를 부르지 않는다").doesNotContainAnyElementsOf(requestThreads);
            assertThat(AI.requests()).as("포트 응답 전에는 ai-server를 부르지 않는다").isEmpty();

            proceed.countDown();
            JsonNode request = awaitAiRequests(1).get(0);
            assertThat(request.get("messageId").asLong()).isEqualTo(pendingExplanationId(memberId, calcRun));
            assertThat(portTimedOut).as("포트 대기가 시간 초과로 풀리면 안 된다").isFalse();
            verify(planContextPort, times(1)).findForCalcRun(memberId, calcRun, Scenario.AVG);
            assertThat(callers).hasSize(1);
        } finally {
            proceed.countDown();
            requester.shutdownNow();
        }
    }

    // ---------- AC-7 ----------

    @Test
    void secondRequestWhileExplanationPendingReusesRow() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");

        explainRun(cookie, calcRun).andExpect(status().isAccepted());
        long firstId = pendingExplanationId(memberId, calcRun);
        assertThat(countRows(memberId)).isEqualTo(1);
        awaitAiRequests(1);

        MvcResult second = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(second);
        assertThat(countRows(memberId)).isEqualTo(1);
        assertThat(statusOf(firstId)).isEqualTo("PENDING");
        // 재사용 때는 ChatAnswerRequested를 다시 발행하지 않는다(결정 U-4 A). ai-server 호출은 첫 요청의 1번뿐
        assertThat(answerEvents()).isEqualTo(1);
        assertThat(AI.requests()).hasSize(1);
    }

    @Test
    void existingPendingExplanationForSameRunIsReused() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        long pending = insertExplanation(memberId, "PENDING", null, calcRun, "AVG", Instant.now());

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(countRows(memberId)).isEqualTo(1);
        assertThat(statusOf(pending)).isEqualTo("PENDING");
        // 재사용 때는 이벤트를 발행하지 않는다(결정 U-4 A)
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    /**
     * 같은 기록에 더 이른 대기 설명(60초 이내)과 더 최근에 끝난 설명이 함께 있으면(허용된 동시 요청의 결과) 대기 설명을 재사용한다.
     * 가장 최근 행 하나의 상태만 보는 구현은 세 번째 행을 만들어 실패한다.
     */
    @Test
    void earlierPendingExplanationIsReusedEvenIfLaterOneCompleted() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        Instant now = Instant.now();
        long earlierPending = insertExplanation(memberId, "PENDING", null, calcRun, "AVG", now.minusSeconds(10));
        long laterCompleted = insertExplanation(memberId, "COMPLETED", "뒤 설명", calcRun, "AVG", now.minusSeconds(5));
        assertThat(laterCompleted).isGreaterThan(earlierPending);

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(countRows(memberId)).isEqualTo(2);
        Map<String, Object> pending = row(earlierPending);
        assertThat(pending.get("status")).isEqualTo("PENDING");
        assertThat(pending.get("message_type")).isEqualTo("EXPLANATION");
        assertThat(pending.get("content")).isNull();
        Map<String, Object> completed = row(laterCompleted);
        assertThat(completed.get("status")).isEqualTo("COMPLETED");
        assertThat(completed.get("content")).isEqualTo("뒤 설명");
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    /**
     * 허용된 동시 요청(잠금 없음)으로 같은 기록에 60초 이내 대기 설명이 두 개 남은 뒤 다시 요청해도 재사용한다.
     * 재사용 조회를 "정확히 한 행"으로 읽어 두 행이면 예외(500)가 나는 구현은 여기서 실패한다.
     */
    @Test
    void twoPendingExplanationsLeftByConcurrentRequestsAreReused() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        Instant now = Instant.now();
        long firstPending = insertExplanation(memberId, "PENDING", null, calcRun, "AVG", now.minusSeconds(10));
        long secondPending = insertExplanation(memberId, "PENDING", null, calcRun, "AVG", now.minusSeconds(9));
        Map<String, Object> firstBefore = row(firstPending);
        Map<String, Object> secondBefore = row(secondPending);

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(countRows(memberId)).isEqualTo(2);
        assertThat(row(firstPending)).isEqualTo(firstBefore);
        assertThat(row(secondPending)).isEqualTo(secondBefore);
        for (long id : List.of(firstPending, secondPending)) {
            Map<String, Object> kept = row(id);
            assertThat(kept.get("status")).isEqualTo("PENDING");
            assertThat(kept.get("message_type")).isEqualTo("EXPLANATION");
            assertThat(kept.get("content")).isNull();
        }
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    @Test
    void completedOrFailedExplanationForSameRunGetsNewPendingRow() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        long completed = insertExplanation(memberId, "COMPLETED", "앞 설명", calcRun, "AVG", Instant.now());
        long failed = jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, content, calc_run_id, "
                        + "baseline_scenario) VALUES (?, 'ASSISTANT', 'FAILURE', 'FAILED', ?, ?, 'AVG') RETURNING id")
                .params(memberId, "지난 실패 안내", calcRun).query(Long.class).single();
        Map<String, Object> completedBefore = row(completed);
        Map<String, Object> failedBefore = row(failed);

        explainRun(cookie, calcRun).andExpect(status().isAccepted());

        assertThat(row(completed)).as("앞 완료 설명은 그대로").isEqualTo(completedBefore);
        assertThat(row(failed)).as("앞 실패 행은 그대로").isEqualTo(failedBefore);

        assertThat(countRows(memberId)).isEqualTo(3);
        List<Map<String, Object>> explanations = explanationRowsOf(memberId);
        assertThat(explanations).hasSize(3);
        assertThat(explanations.get(2).get("status")).isEqualTo("PENDING");
        assertThat(explanations.get(2).get("message_type")).isEqualTo("EXPLANATION");
        assertThat(explanations.get(2).get("baseline_scenario")).isEqualTo("AVG");
    }

    /**
     * 같은 calcRunId의 앞 설명이 FAILED(FAILURE)뿐이면 재사용하지 않고 새 대기 행을 만든다.
     * 앞 실패 행은 상태·종류·본문까지 그대로다(본문을 chat.failed와 다르게 두어 덮어쓰기를 잡는다).
     */
    @Test
    void failedExplanationForSameRunGetsNewPendingRow() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        long failed = jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, content, calc_run_id, "
                        + "baseline_scenario) VALUES (?, 'ASSISTANT', 'FAILURE', 'FAILED', ?, ?, 'AVG') RETURNING id")
                .params(memberId, "지난 실패 안내", calcRun).query(Long.class).single();
        Map<String, Object> failedBefore = row(failed);

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(countRows(memberId)).isEqualTo(2);
        assertThat(row(failed)).as("요청 직후 앞 실패 행은 그대로").isEqualTo(failedBefore);
        long fresh = pendingExplanationId(memberId, calcRun);
        assertThat(fresh).isGreaterThan(failed);
        assertThat(row(fresh).get("message_type")).isEqualTo("EXPLANATION");
        assertThat(awaitAiRequests(1).get(0).get("messageId").asLong()).isEqualTo(fresh);
        assertThat(answerEvents()).isEqualTo(1);

        AI.release(200, explanationReply("새 설명", calcRun));
        awaitStatus(fresh, "COMPLETED");
        awaitAnswerEventsCompleted();
        assertThat(row(failed)).as("새 설명이 끝난 뒤에도 앞 실패 행은 그대로").isEqualTo(failedBefore);
        assertThat(row(failed).get("status")).isEqualTo("FAILED");
        assertThat(row(failed).get("message_type")).isEqualTo("FAILURE");
        assertThat(row(failed).get("content")).isEqualTo("지난 실패 안내");
        assertThat(row(fresh).get("content")).isEqualTo("새 설명");
        assertThat(countRows(memberId)).isEqualTo(2);
    }

    @Test
    void explanationAfterPreviousOneCompletedCreatesNewPendingRow() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        explainRun(cookie, calcRun).andExpect(status().isAccepted());
        long firstId = pendingExplanationId(memberId, calcRun);
        awaitAiRequests(1);
        AI.release(200, """
                {"messageType":"EXPLANATION","content":"첫 설명","baselineScenario":"AVG","calcRunId":%d,
                 "conditionChanges":null,"citationChunkIds":[]}""".formatted(calcRun));
        awaitStatus(firstId, "COMPLETED");
        // 두 번째 EXPLAIN 응답은 잡아 둔다. 그래서 새 행이 처음부터 PENDING으로 저장됐는지 볼 수 있다
        AI.hold();

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(countRows(memberId)).isEqualTo(2);
        long secondId = jdbc.sql("SELECT max(id) FROM chat_message WHERE member_id = ?").param(memberId)
                .query(Long.class).single();
        assertThat(secondId).isGreaterThan(firstId);
        JsonNode secondRequest = awaitAiRequests(2).get(1);
        assertThat(secondRequest.get("mode").asString()).isEqualTo("EXPLAIN");
        assertThat(secondRequest.get("messageId").asLong()).isEqualTo(secondId);
        Map<String, Object> second = row(secondId);
        assertThat(second.get("role")).isEqualTo("ASSISTANT");
        assertThat(second.get("message_type")).isEqualTo("EXPLANATION");
        assertThat(second.get("status")).isEqualTo("PENDING");
        assertThat(second.get("content")).isNull();
        assertThat(second.get("calc_run_id")).isEqualTo(calcRun);
        assertThat(answerEvents()).isEqualTo(2);
        Map<String, Object> first = row(firstId);
        assertThat(first.get("status")).isEqualTo("COMPLETED");
        assertThat(first.get("content")).isEqualTo("첫 설명");
    }

    @Test
    void pendingExplanationForAnotherRunDoesNotPreventNewRow() throws Exception {
        long oldRun = planRunOf(memberId, "AVG");
        insertExplanation(memberId, "PENDING", null, oldRun, "AVG", Instant.now());
        long newRun = planRunOf(memberId, "AVG");

        explainRun(cookie, newRun).andExpect(status().isAccepted());

        assertThat(countRows(memberId)).isEqualTo(2);
        assertThat(pendingExplanationId(memberId, newRun)).isPositive();
    }

    /** 재사용은 그 회원의 행만이다. 같은 calcRunId를 가리키는 다른 회원의 대기 설명은 재사용하지 않고 건드리지도 않는다. */
    @Test
    void othersPendingExplanationOnSameRunIsNotReused() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        long othersPending = insertExplanation(otherMemberId, "PENDING", null, calcRun, "AVG", Instant.now());

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(countRows(memberId)).isEqualTo(1);
        assertThat(countRows(otherMemberId)).isEqualTo(1);
        Map<String, Object> others = row(othersPending);
        assertThat(others.get("status")).isEqualTo("PENDING");
        assertThat(others.get("message_type")).isEqualTo("EXPLANATION");
        assertThat(others.get("content")).isNull();
        long mine = pendingExplanationId(memberId, calcRun);
        assertThat(mine).isNotEqualTo(othersPending);
        JsonNode request = awaitAiRequests(1).get(0);
        assertThat(request.get("messageId").asLong()).isEqualTo(mine);
        assertThat(answerEvents()).isEqualTo(1);
        assertThat(AI.requests()).hasSize(1);
    }

    // ---------- AC-8 ----------

    @Test
    void pendingQuestionAnswerDoesNotBlockExplanation() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        long pendingAnswer = insertMessage(memberId, "ASSISTANT", "ANSWER", "PENDING", null);

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("ANSWER_PENDING");
        assertThat(countRows(memberId)).isEqualTo(2);
        assertThat(pendingExplanationId(memberId, calcRun)).isPositive();
        Map<String, Object> answer = row(pendingAnswer);
        assertThat(answer.get("status")).isEqualTo("PENDING");
        assertThat(answer.get("message_type")).isEqualTo("ANSWER");
    }

    /** 같은 calcRunId를 가리키는 대기 ANSWER는 설명이 아니므로 재사용하지 않고 EXPLANATION 대기 행을 새로 만든다. */
    @Test
    void pendingAnswerOnSameRunIsNotReusedAsExplanation() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        long pendingAnswer = jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, calc_run_id, "
                        + "baseline_scenario) VALUES (?, 'ASSISTANT', 'ANSWER', 'PENDING', ?, 'AVG') RETURNING id")
                .params(memberId, calcRun).query(Long.class).single();

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        assertThat(countRows(memberId)).isEqualTo(2);
        Map<String, Object> answer = row(pendingAnswer);
        assertThat(answer.get("status")).isEqualTo("PENDING");
        assertThat(answer.get("message_type")).isEqualTo("ANSWER");
        assertThat(answer.get("content")).isNull();
        long explanation = pendingExplanationId(memberId, calcRun);
        assertThat(explanation).isNotEqualTo(pendingAnswer);
        assertThat(row(explanation).get("role")).isEqualTo("ASSISTANT");
        assertThat(awaitAiRequests(1).get(0).get("messageId").asLong()).isEqualTo(explanation);
        assertThat(answerEvents()).isEqualTo(1);
    }

    // ---------- AC-9 ----------

    @Test
    void stalePendingIsFailedBeforeExplanation() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long calcRun = planRunOf(memberId, "AVG");
        long otherRun = insertCalcRun(memberId, "PLAN");
        long staleAnswer = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(61)));
        long staleExplanation = insertExplanation(memberId, "PENDING", null, otherRun, "AVG",
                now.minus(Duration.ofSeconds(61)));
        long othersStale = insertMessageAt(otherMemberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(120)));
        // 같은 회원의 61초 된 COMPLETED 설명은 PENDING이 아니라 정리 대상이 아니다
        long oldCompleted = insertExplanation(memberId, "COMPLETED", "지난 설명", otherRun, "AVG",
                now.minus(Duration.ofSeconds(61)));
        // 같은 회원의 61초 된 FAILED 행도 정리 대상이 아니다. 기존 실패 안내 본문(chat.failed와 다른 문장)을 덮어쓰지 않는다
        long oldFailed = jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, content, "
                        + "created_at) VALUES (?, 'ASSISTANT', 'FAILURE', 'FAILED', '지난 실패 안내', ?) RETURNING id")
                .params(memberId, OffsetDateTime.ofInstant(now.minus(Duration.ofSeconds(61)), ZoneOffset.UTC))
                .query(Long.class).single();
        assertThat(failedSentence()).isNotEqualTo("지난 실패 안내");

        explainRun(cookie, calcRun).andExpect(status().isAccepted());

        Map<String, Object> completed = row(oldCompleted);
        assertThat(completed.get("status")).isEqualTo("COMPLETED");
        assertThat(completed.get("message_type")).isEqualTo("EXPLANATION");
        assertThat(completed.get("content")).isEqualTo("지난 설명");
        Map<String, Object> failedBefore = row(oldFailed);
        assertThat(failedBefore.get("status")).isEqualTo("FAILED");
        assertThat(failedBefore.get("message_type")).isEqualTo("FAILURE");
        assertThat(failedBefore.get("content")).isEqualTo("지난 실패 안내");
        for (long id : List.of(staleAnswer, staleExplanation)) {
            Map<String, Object> cleaned = row(id);
            assertThat(cleaned.get("status")).isEqualTo("FAILED");
            assertThat(cleaned.get("message_type")).isEqualTo("FAILURE");
            assertThat(cleaned.get("content")).isEqualTo(failedSentence());
        }
        assertThat(row(othersStale).get("status")).isEqualTo("PENDING");
        assertThat(pendingExplanationId(memberId, calcRun)).isPositive();
    }

    /** 정리가 먼저라서, 같은 calcRunId의 61초 된 대기 설명은 재사용되지 않고 FAILED가 된 뒤 새 대기 행이 생긴다. */
    @Test
    void staleExplanationForSameRunIsFailedThenNewRowIsCreated() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long calcRun = planRunOf(memberId, "AVG");
        long stale = insertExplanation(memberId, "PENDING", null, calcRun, "AVG",
                now.minus(Duration.ofSeconds(61)));

        explainRun(cookie, calcRun).andExpect(status().isAccepted());

        Map<String, Object> cleaned = row(stale);
        assertThat(cleaned.get("status")).isEqualTo("FAILED");
        assertThat(cleaned.get("message_type")).isEqualTo("FAILURE");
        assertThat(countRows(memberId)).isEqualTo(2);
        long fresh = pendingExplanationId(memberId, calcRun);
        assertThat(fresh).isNotEqualTo(stale);
    }

    /** 정리 조건은 엄격히 60초 초과다. 정확히 60초 된 같은 calcRunId 대기 설명은 그대로 두고 재사용한다. */
    @Test
    void explanationPendingExactly60SecondsIsReusedNotCleaned() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long calcRun = planRunOf(memberId, "AVG");
        long boundary = insertExplanation(memberId, "PENDING", null, calcRun, "AVG",
                now.minus(Duration.ofSeconds(60)));

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        Map<String, Object> kept = row(boundary);
        assertThat(kept.get("status")).isEqualTo("PENDING");
        assertThat(kept.get("message_type")).isEqualTo("EXPLANATION");
        assertThat(kept.get("content")).isNull();
        assertThat(countRows(memberId)).isEqualTo(1);
        // 재사용이므로 같은 행 id로도 다시 발행하지 않는다(결정 U-4 A)
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    /** DB 정밀도(마이크로초)로 60초를 막 넘긴 같은 calcRunId 대기 설명은 초 단위로 버리지 않고 정리한 뒤 새 행을 만든다. */
    @Test
    void explanationPendingJustOver60SecondsIsCleanedNotReused() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long calcRun = planRunOf(memberId, "AVG");
        long stale = insertExplanation(memberId, "PENDING", null, calcRun, "AVG",
                now.minus(Duration.ofSeconds(60).plus(1, ChronoUnit.MICROS)));

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        Map<String, Object> cleaned = row(stale);
        assertThat(cleaned.get("status")).isEqualTo("FAILED");
        assertThat(cleaned.get("message_type")).isEqualTo("FAILURE");
        assertThat(cleaned.get("content")).isEqualTo(failedSentence());
        assertThat(countRows(memberId)).isEqualTo(2);
        long fresh = pendingExplanationId(memberId, calcRun);
        assertThat(fresh).isNotEqualTo(stale);
        assertThat(row(fresh).get("message_type")).isEqualTo("EXPLANATION");
    }

    /**
     * 실제 F-3 두 번: 첫 EXPLAIN 응답이 붙잡힌 동안 61초가 지나 다시 요청하면 첫 행이 정리되고 새 행이 생긴다.
     * 그 뒤 첫 응답이 늦게 와도 정리된 첫 행은 FAILED·FAILURE·chat.failed 그대로이고, 새 행은 바뀌지 않으며 자기 응답으로만 끝난다.
     */
    @Test
    void lateAnswerForCleanedExplanationDoesNotTouchEitherRow() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        explainRun(cookie, calcRun).andExpect(status().isAccepted());
        long firstId = pendingExplanationId(memberId, calcRun);
        awaitAiRequests(1);
        Instant firstCreated = jdbc.sql("SELECT created_at FROM chat_message WHERE id = ?").param(firstId)
                .query(OffsetDateTime.class).single().toInstant();

        // 두 번째 EXPLAIN은 늦게 답하게 해 두고, 첫 요청은 계속 붙잡아 둔다
        AI.respondAfter(Duration.ofSeconds(5), 200, explanationReply("새 설명", calcRun));
        clock.fixAt(firstCreated.plusSeconds(61));
        MvcResult second = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(second);
        assertThat(statusOf(firstId)).isEqualTo("FAILED");
        long secondId = pendingExplanationId(memberId, calcRun);
        assertThat(secondId).isGreaterThan(firstId);
        assertThat(awaitAiRequests(2).get(1).get("messageId").asLong()).isEqualTo(secondId);

        // 첫 응답이 늦게 도착한다
        AI.release(200, explanationReply("늦은 첫 설명", calcRun));
        await().atMost(WAIT).until(() -> incompleteAnswerEvents() == 1);

        Map<String, Object> first = row(firstId);
        assertThat(first.get("status")).isEqualTo("FAILED");
        assertThat(first.get("message_type")).isEqualTo("FAILURE");
        assertThat(first.get("content")).isEqualTo(failedSentence());
        Map<String, Object> pending = row(secondId);
        assertThat(pending.get("status")).isEqualTo("PENDING");
        assertThat(pending.get("content")).isNull();

        awaitStatus(secondId, "COMPLETED");
        assertThat(row(secondId).get("content")).isEqualTo("새 설명");
        assertThat(row(firstId).get("status")).isEqualTo("FAILED");
        assertThat(row(firstId).get("content")).isEqualTo(failedSentence());
        assertThat(countRows(memberId)).isEqualTo(2);
    }

    @Test
    void nonIntegerCalcRunIdOrNonObjectBodyReturns400() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        List<String> bodies = List.of(
                "{\"calcRunId\":\"" + calcRun + "\"}",
                "{\"calcRunId\":" + calcRun + ".5}",
                // 값이 정수와 같은 소수: 소수부가 0일 때 long으로 바꿔 받는 구현이면 202가 된다
                "{\"calcRunId\":" + calcRun + ".0}",
                "{\"calcRunId\":" + calcRun + "e0}",
                "{\"calcRunId\":\"abc\"}",
                "{\"calcRunId\":true}",
                "{\"calcRunId\":{}}",
                "{\"calcRunId\":[" + calcRun + "]}",
                "[" + calcRun + "]",
                String.valueOf(calcRun),
                "\"" + calcRun + "\"",
                "null",
                "",
                "{\"calcRunId\":",
                // 첫 객체만 읽고 뒤를 버리면 202가 되는 본문: 전체가 하나의 객체가 아니다
                "{\"calcRunId\":" + calcRun + "} {}",
                "{\"calcRunId\":" + calcRun + "}{\"calcRunId\":" + calcRun + "}",
                "{\"calcRunId\":" + calcRun + "} x");
        for (String body : bodies) {
            explain(cookie, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.errors").isEmpty());
        }

        assertThat(allRows()).isZero();
        assertThat(answerEvents()).isZero();
    }

    /** calcRunId가 없거나 null이거나 long 범위를 넘는 정수도 400 MALFORMED_REQUEST다(결정 U-2 A). */
    @Test
    void missingNullOrOutOfRangeCalcRunIdReturns400() throws Exception {
        planRunOf(memberId, "AVG");
        List<String> bodies = List.of(
                "{}",
                "{\"calcRunId\":null}",
                "{\"calcRunId\":9223372036854775808}",
                "{\"calcRunId\":99999999999999999999}",
                "{\"calcRunId\":-9223372036854775809}");
        for (String body : bodies) {
            explain(cookie, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.errors").isEmpty());
        }

        assertThat(allRows()).isZero();
        assertThat(answerEvents()).isZero();
    }

    /**
     * 형식 오류(400)는 처리 전에 거절하므로 오래된 PENDING 정리도 하지 않는다(잠정 결정 P-32, F-1이 검증 뒤에 정리하는 것과 같음).
     * 검증 전에 정리해 커밋하고 400을 내는 구현은 여기서 실패한다.
     */
    @Test
    void malformedRequestDoesNotCleanStalePending() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long calcRun = planRunOf(memberId, "AVG");
        long stale = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(61)));
        Map<String, Object> before = row(stale);
        List<String> bodies = List.of(
                "{}",
                "{\"calcRunId\":null}",
                "{\"calcRunId\":\"" + calcRun + "\"}",
                "{\"calcRunId\":9223372036854775808}",
                "[" + calcRun + "]",
                "");

        for (String body : bodies) {
            explain(cookie, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.errors").isEmpty());
            assertThat(row(stale)).as("400 뒤에도 오래된 대기 행은 그대로: " + body).isEqualTo(before);
        }

        Map<String, Object> kept = row(stale);
        assertThat(kept.get("status")).isEqualTo("PENDING");
        assertThat(kept.get("message_type")).isEqualTo("ANSWER");
        assertThat(kept.get("content")).isNull();
        assertThat(countRows(memberId)).isEqualTo(1);
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    /** 404로 끝나도 앞서 한 오래된 PENDING 정리는 롤백되지 않는다(결정 U-3 A, F-1의 noRollbackFor와 같은 방식). */
    @Test
    void stalePendingCleanupIsKeptWhenRequestEndsIn404() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long othersRun = planRunOf(otherMemberId, "AVG");
        long myChatRun = insertCalcRun(memberId, "CHAT");
        long staleAnswer = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(61)));
        long staleExplanation = insertExplanation(memberId, "PENDING", null, myChatRun, "AVG",
                now.minus(Duration.ofSeconds(61)));

        explainRun(cookie, othersRun)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        for (long id : List.of(staleAnswer, staleExplanation)) {
            Map<String, Object> cleaned = row(id);
            assertThat(cleaned.get("status")).isEqualTo("FAILED");
            assertThat(cleaned.get("message_type")).isEqualTo("FAILURE");
            assertThat(cleaned.get("content")).isEqualTo(failedSentence());
        }
        assertThat(countRows(memberId)).isEqualTo(2);
        assertThat(answerEvents()).isZero();
    }

    /**
     * 404 세 경우(없는 기록·남의 PLAN 기록·내 CHAT 기록) 모두 앞서 한 정리가 남는다(결정 U-3 A).
     * 경우마다 61초 된 대기 행을 따로 준비해, 앞 요청의 정리가 다음 판정에 섞이지 않게 한다.
     * 남의 기록에서만 정리를 커밋하고 다른 404에서는 되돌리는 구현은 여기서 실패한다.
     */
    @Test
    void stalePendingCleanupIsKeptForEvery404Case() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long othersRun = planRunOf(otherMemberId, "AVG");
        long myChatRun = insertCalcRun(memberId, "CHAT");
        long missing = Math.max(othersRun, myChatRun) + 1_000_000L;

        List<Long> staleRows = new ArrayList<>();
        for (long calcRunId : List.of(missing, othersRun, myChatRun)) {
            long stale = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                    now.minus(Duration.ofSeconds(61)));
            staleRows.add(stale);

            explainRun(cookie, calcRunId)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.errors").isEmpty());

            Map<String, Object> cleaned = row(stale);
            assertThat(cleaned.get("status")).as("404 뒤에도 정리 유지: " + calcRunId).isEqualTo("FAILED");
            assertThat(cleaned.get("message_type")).isEqualTo("FAILURE");
            assertThat(cleaned.get("content")).isEqualTo(failedSentence());
        }

        assertThat(countRows(memberId)).isEqualTo(staleRows.size());
        assertThat(countRows(otherMemberId)).isZero();
        assertThat(answerEvents()).isZero();
        assertThat(AI.requests()).isEmpty();
    }

    // ---------- AC-10 ----------

    /**
     * 실제 초과 계획: 그 기록이 가리키는 계획 버전에서 보유 자산이 목표액과 같다(보유 자산 ≥ 목표).
     * Spring은 DB의 보유 자산·목표액으로 따로 분기하지 않고 평소대로 저장·발행한다. 초과 계획이면 202만 내고 저장하지 않는 구현은
     * 여기서 실패한다. 그 기록의 planContext(초과)는 같은 버전 기준으로 포트 스텁이 돌려준다.
     */
    @Test
    void alreadyExceededPlanIsProcessedNormallyAndStoresAiFixedSentence() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        jdbc.sql("UPDATE plan_version SET held_assets = goal_amount "
                + "WHERE id = (SELECT plan_version_id FROM calc_run WHERE id = ?)").param(calcRun).update();
        assertThat(jdbc.sql("SELECT pv.held_assets >= pv.goal_amount FROM plan_version pv "
                        + "JOIN calc_run cr ON cr.plan_version_id = pv.id WHERE cr.id = ?")
                .param(calcRun).query(Boolean.class).single()).as("픽스처가 실제 초과 계획").isTrue();

        assertExceededPlanIsExplainedNormally(calcRun);
    }

    /**
     * 보유 자산이 목표액보다 큰 초과 계획도 같다. 등호(=)만 픽스처로 두면 "held_assets > goal_amount일 때만 건너뛰는"
     * 구현이 통과하므로 엄격히 큰 경우를 따로 본다.
     */
    @Test
    void planWithHeldAssetsAboveGoalIsProcessedNormallyAndStoresAiFixedSentence() throws Exception {
        long calcRun = planRunOf(memberId, "AVG");
        jdbc.sql("UPDATE plan_version SET held_assets = goal_amount + 1 "
                + "WHERE id = (SELECT plan_version_id FROM calc_run WHERE id = ?)").param(calcRun).update();
        assertThat(jdbc.sql("SELECT pv.held_assets > pv.goal_amount FROM plan_version pv "
                        + "JOIN calc_run cr ON cr.plan_version_id = pv.id WHERE cr.id = ?")
                .param(calcRun).query(Boolean.class).single()).as("픽스처가 보유 자산 > 목표액").isTrue();

        assertExceededPlanIsExplainedNormally(calcRun);
    }

    /** 초과 계획 기록으로 요청: 평소대로 대기 행 저장·발행·EXPLAIN 발신, ai-server 고정 문장을 그 행에 저장한다. */
    private void assertExceededPlanIsExplainedNormally(long calcRun) throws Exception {
        JsonNode exceeded = contextOf(calcRun, Scenario.AVG, "ALREADY_EXCEEDED");
        given(planContextPort.findForCalcRun(memberId, calcRun, Scenario.AVG))
                .willReturn(Optional.of(new PlanContextSnapshot(Scenario.AVG, exceeded)));

        MvcResult result = explainRun(cookie, calcRun).andExpect(status().isAccepted()).andReturn();

        assertEmptyObject(result);
        long explanationId = pendingExplanationId(memberId, calcRun);
        Map<String, Object> saved = row(explanationId);
        assertThat(saved.get("role")).isEqualTo("ASSISTANT");
        assertThat(saved.get("message_type")).isEqualTo("EXPLANATION");
        assertThat(saved.get("status")).isEqualTo("PENDING");
        assertThat(saved.get("content")).isNull();
        assertThat(saved.get("baseline_scenario")).isEqualTo("AVG");
        JsonNode request = awaitAiRequests(1).get(0);

        assertThat(request.get("mode").asString()).isEqualTo("EXPLAIN");
        assertThat(request.get("messageId").asLong()).isEqualTo(explanationId);
        assertThat(request.get("calcRunId").asLong()).isEqualTo(calcRun);
        assertThat(request.get("planContext")).isEqualTo(exceeded);
        assertThat(request.get("planContext").get("result").get("status").asString()).isEqualTo("ALREADY_EXCEEDED");
        verify(planContextPort).findForCalcRun(memberId, calcRun, Scenario.AVG);
        assertThat(answerEvents()).isEqualTo(1);

        String fixed = copy.get("chat.alreadyExceeded");
        AI.release(200, JSON.writeValueAsString(Map.of("messageType", "EXPLANATION", "content", fixed,
                "baselineScenario", "AVG", "calcRunId", calcRun, "citationChunkIds", List.of())));
        awaitStatus(explanationId, "COMPLETED");

        Map<String, Object> done = row(explanationId);
        assertThat(done.get("message_type")).isEqualTo("EXPLANATION");
        assertThat(done.get("content")).isEqualTo(fixed);
        assertThat(countRows(memberId)).isEqualTo(1);
    }
}
