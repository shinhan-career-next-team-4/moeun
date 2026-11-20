package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.moeun.backend.auth.application.JwtProperties;
import com.moeun.backend.auth.application.JwtTokenService;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;

/** F-2 메시지 폴링·복원 GET /api/chat/messages (#42, 계약 C12b-3·C12b-5·C12b-11-3). */
class ChatPollingApiTest extends ChatIntegrationTestSupport {

    private static final List<String> MESSAGE_KEYS =
            List.of("id", "role", "messageType", "status", "content", "citations", "createdAt");

    @Autowired
    JwtProperties jwtProperties;

    // ---------- 요청·관찰 도우미 ----------

    ResultActions getMessages(Cookie who, String afterId) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/chat/messages");
        if (who != null) {
            request.cookie(who);
        }
        if (afterId != null) {
            request.param("afterId", afterId);
        }
        return mockMvc.perform(request);
    }

    /** 200을 확인하고 본문 JSON을 돌려준다. */
    JsonNode poll(Cookie who, Long afterId) throws Exception {
        String body = getMessages(who, afterId == null ? null : String.valueOf(afterId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }

    static List<Long> ids(JsonNode body) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode message : body.get("messages")) {
            ids.add(message.get("id").asLong());
        }
        return ids;
    }

    static JsonNode messageOf(JsonNode body, long id) {
        for (JsonNode message : body.get("messages")) {
            if (message.get("id").asLong() == id) {
                return message;
            }
        }
        throw new AssertionError("응답에 메시지 " + id + "가 없어요: " + body);
    }

    long insertQuestions(long member, int count) {
        long last = 0;
        for (int i = 0; i < count; i++) {
            last = insertMessage(member, "USER", "QUESTION", "COMPLETED", "질문 " + i);
        }
        return last;
    }

    /** 기관·문서·chunk 한 벌을 만들고 chunk id를 돌려준다. 같은 URL이면 같은 문서를 다시 쓴다. */
    long sourceChunk(String orgName, String title, String url) {
        long org = jdbc.sql("INSERT INTO source_org (name, org_type) VALUES (?, 'ASSET_MANAGER') "
                        + "ON CONFLICT ON CONSTRAINT uq_source_org_name DO UPDATE SET org_type = EXCLUDED.org_type "
                        + "RETURNING id")
                .param(orgName).query(Long.class).single();
        long document = jdbc.sql("INSERT INTO rag_document (doc_type, source_org_id, title, source_url, "
                        + "content_sha256) VALUES ('DISCLOSURE', ?, ?, ?, ?) "
                        + "ON CONFLICT ON CONSTRAINT uq_rag_document_rev DO UPDATE SET title = EXCLUDED.title "
                        + "RETURNING id")
                .params(org, title, url, "c".repeat(64)).query(Long.class).single();
        return jdbc.sql("INSERT INTO rag_chunk (document_id, chunk_no, content) VALUES (?, 1, 'chunk') "
                        + "ON CONFLICT ON CONSTRAINT uq_rag_chunk_doc_no DO UPDATE SET content = EXCLUDED.content "
                        + "RETURNING id")
                .param(document).query(Long.class).single();
    }

    void cite(long messageId, int rank, long chunkId) {
        jdbc.sql("INSERT INTO chat_message_citation (message_id, rank, chunk_id) VALUES (?, ?, ?)")
                .params(messageId, rank, chunkId).update();
    }

    // ---------- AC-1 PENDING은 messageType·content를 가린다 ----------

    @Test
    void pendingAnswerIsShownWithNullTypeAndContent() throws Exception {
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "월 50만원이면?");
        long pending = insertMessage(memberId, "ASSISTANT", "ANSWER", "PENDING", null);

        JsonNode body = poll(cookie, question - 1);

        assertThat(ids(body)).containsExactly(question, pending);
        JsonNode message = messageOf(body, pending);
        assertThat(message.get("status").asString()).isEqualTo("PENDING");
        assertThat(message.get("role").asString()).isEqualTo("ASSISTANT");
        assertThat(message.has("messageType")).isTrue();
        assertThat(message.get("messageType").isNull()).isTrue();
        assertThat(message.has("content")).isTrue();
        assertThat(message.get("content").isNull()).isTrue();
        assertThat(message.get("citations").isArray()).isTrue();
        assertThat(message.get("citations").size()).isZero();
    }

    /** DB의 PENDING 행(질문 대기·설명 대기)에 본문·출처가 남아 있어도(제약상 허용) 복원·폴링 모두 화면에는 가린다. */
    @Test
    void pendingHidesStoredContentAndCitations() throws Exception {
        long chunk = sourceChunk("poll-org-A", "A 운용사 간이투자설명서", "https://example.com/poll/a");
        long pendingAnswer = insertMessage(memberId, "ASSISTANT", "ANSWER", "PENDING", "작성 중이던 본문");
        cite(pendingAnswer, 1, chunk);
        long calcRun = insertCalcRun(memberId, "PLAN");
        long pendingExplanation = insertExplanation(memberId, "PENDING", "작성 중이던 설명", calcRun, "AVG",
                Instant.now());
        cite(pendingExplanation, 1, chunk);

        for (JsonNode body : List.of(poll(cookie, null), poll(cookie, pendingAnswer - 1))) {
            assertThat(ids(body)).containsExactly(pendingAnswer, pendingExplanation);
            for (long pending : List.of(pendingAnswer, pendingExplanation)) {
                JsonNode message = messageOf(body, pending);
                assertThat(message.get("status").asString()).isEqualTo("PENDING");
                assertThat(message.has("messageType")).isTrue();
                assertThat(message.get("messageType").isNull()).isTrue();
                assertThat(message.has("content")).isTrue();
                assertThat(message.get("content").isNull()).isTrue();
                assertThat(message.get("citations").isArray()).isTrue();
                assertThat(message.get("citations").size()).isZero();
            }
            assertThat(body.toString()).doesNotContain("작성 중이던").doesNotContain("poll-org-A");
        }
    }

    @Test
    void pendingExplanationIsAlsoShownWithNullTypeAndContent() throws Exception {
        long calcRun = insertCalcRun(memberId, "PLAN");
        long pending = insertExplanation(memberId, "PENDING", null, calcRun, "AVG", Instant.now());

        JsonNode message = messageOf(poll(cookie, null), pending);

        assertThat(message.get("status").asString()).isEqualTo("PENDING");
        assertThat(message.get("messageType").isNull()).isTrue();
        assertThat(message.get("content").isNull()).isTrue();
    }

    @Test
    void completedMessagesKeepDbTypeAndContent() throws Exception {
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        long answer = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "답변 문장");
        long calcRun = insertCalcRun(memberId, "PLAN");
        long explanation = insertExplanation(memberId, "COMPLETED", "설명 문장", calcRun, "AVG", Instant.now());

        JsonNode body = poll(cookie, null);

        JsonNode user = messageOf(body, question);
        assertThat(user.get("role").asString()).isEqualTo("USER");
        assertThat(user.get("messageType").asString()).isEqualTo("QUESTION");
        assertThat(user.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(user.get("content").asString()).isEqualTo("질문");
        assertThat(messageOf(body, answer).get("messageType").asString()).isEqualTo("ANSWER");
        assertThat(messageOf(body, answer).get("content").asString()).isEqualTo("답변 문장");
        assertThat(messageOf(body, explanation).get("messageType").asString()).isEqualTo("EXPLANATION");
        assertThat(messageOf(body, explanation).get("content").asString()).isEqualTo("설명 문장");
    }

    // ---------- AC-2 FAILED는 FAILURE + 문구표 실패 안내 ----------

    @Test
    void failedMessageIsFailureWithFailedSentence() throws Exception {
        long failed = insertMessage(memberId, "ASSISTANT", "FAILURE", "FAILED", failedSentence());

        JsonNode message = messageOf(poll(cookie, null), failed);

        assertThat(message.get("status").asString()).isEqualTo("FAILED");
        assertThat(message.get("messageType").asString()).isEqualTo("FAILURE");
        assertThat(message.get("content").asString()).isEqualTo(failedSentence());
        assertThat(message.get("citations").size()).isZero();
    }

    /**
     * 사용자 확정 B2: 저장 당시 본문이 무엇이든 현재 문구표 chat.failed를 내보낸다.
     * FAILED는 DB message_type이 FAILURE가 아니어도 항상 messageType=FAILURE다(C12b-3).
     */
    @Test
    void failedMessageAlwaysShowsCurrentFailedSentenceNotStoredContent() throws Exception {
        long failed = insertMessage(memberId, "ASSISTANT", "FAILURE", "FAILED", "예전에 저장된 다른 실패 문구");
        long failedAnswer = insertMessage(memberId, "ASSISTANT", "ANSWER", "FAILED", "답변 유형으로 남은 실패 행");

        for (JsonNode body : List.of(poll(cookie, null), poll(cookie, failed - 1))) {
            for (long id : List.of(failed, failedAnswer)) {
                JsonNode message = messageOf(body, id);
                assertThat(message.get("status").asString()).isEqualTo("FAILED");
                assertThat(message.get("messageType").asString()).isEqualTo("FAILURE");
                assertThat(message.get("content").asString()).isEqualTo(failedSentence());
            }
            assertThat(body.toString()).doesNotContain("예전에 저장된").doesNotContain("답변 유형으로 남은");
        }
    }

    /** 사용자 확정 B3: FAILED 행은 출처 연결이 남아 있어도 복원·폴링 모두 citations=[]다. 출처는 COMPLETED에만. */
    @Test
    void failedMessageHidesLeftoverCitations() throws Exception {
        long chunk = sourceChunk("poll-org-F", "F 운용사 투자설명서", "https://example.com/poll/f");
        long failed = insertMessage(memberId, "ASSISTANT", "FAILURE", "FAILED", failedSentence());
        cite(failed, 1, chunk);
        long completed = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "완료된 답변");
        cite(completed, 1, chunk);

        for (JsonNode body : List.of(poll(cookie, null), poll(cookie, failed - 1))) {
            JsonNode failedMessage = messageOf(body, failed);
            assertThat(failedMessage.get("citations").isArray()).isTrue();
            assertThat(failedMessage.get("citations").size()).isZero();
            JsonNode completedCitations = messageOf(body, completed).get("citations");
            assertThat(completedCitations.size()).isEqualTo(1);
            assertThat(completedCitations.get(0).get("orgName").asString()).isEqualTo("poll-org-F");
        }
    }

    /** 사용자 확정 B3: 오래된 PENDING이 정리되어 FAILED가 된 행도 남은 출처를 싣지 않는다. */
    @Test
    void stalePendingCleanedToFailedHasNoCitations() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long stale = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(61)));
        cite(stale, 1, sourceChunk("poll-org-F", "F 운용사 투자설명서", "https://example.com/poll/f"));

        JsonNode message = messageOf(poll(cookie, stale - 1), stale);

        assertThat(message.get("status").asString()).isEqualTo("FAILED");
        assertThat(message.get("citations").isArray()).isTrue();
        assertThat(message.get("citations").size()).isZero();
    }

    // ---------- AC-3 출처: 제목·URL·기관, 배열 순서 = 순위 ----------

    @Test
    void citationsComeInRankOrderWithTitleUrlAndOrg() throws Exception {
        long second = sourceChunk("poll-org-B", "B 운용사 투자설명서", "https://example.com/poll/b");
        long first = sourceChunk("poll-org-A", "A 운용사 간이투자설명서", "https://example.com/poll/a");
        long answer = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "위험 설명");
        cite(answer, 2, second);   // 순위 2를 먼저 넣어도 순위 순으로 나와야 한다
        cite(answer, 1, first);

        JsonNode citations = messageOf(poll(cookie, answer - 1), answer).get("citations");

        assertThat(citations.size()).isEqualTo(2);
        assertThat(citations.get(0).get("title").asString()).isEqualTo("A 운용사 간이투자설명서");
        assertThat(citations.get(0).get("sourceUrl").asString()).isEqualTo("https://example.com/poll/a");
        assertThat(citations.get(0).get("orgName").asString()).isEqualTo("poll-org-A");
        assertThat(citations.get(1).get("title").asString()).isEqualTo("B 운용사 투자설명서");
        assertThat(citations.get(1).get("sourceUrl").asString()).isEqualTo("https://example.com/poll/b");
        assertThat(citations.get(1).get("orgName").asString()).isEqualTo("poll-org-B");
        for (JsonNode citation : citations) {
            assertThat(citation.propertyNames()).containsExactlyInAnyOrder("title", "sourceUrl", "orgName");
        }
    }

    @Test
    void citationsStayWithTheirOwnMessage() throws Exception {
        long chunkA = sourceChunk("poll-org-A", "A 운용사 간이투자설명서", "https://example.com/poll/a");
        long chunkB = sourceChunk("poll-org-B", "B 운용사 투자설명서", "https://example.com/poll/b");
        long first = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "첫 답변");
        long plain = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "출처 없는 답변");
        long second = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "둘째 답변");
        cite(first, 1, chunkA);
        cite(second, 1, chunkB);
        cite(second, 2, chunkA);

        JsonNode body = poll(cookie, null);

        assertThat(messageOf(body, first).get("citations").size()).isEqualTo(1);
        assertThat(messageOf(body, first).get("citations").get(0).get("orgName").asString())
                .isEqualTo("poll-org-A");
        assertThat(messageOf(body, plain).get("citations").isArray()).isTrue();
        assertThat(messageOf(body, plain).get("citations").size()).isZero();
        JsonNode secondCitations = messageOf(body, second).get("citations");
        assertThat(secondCitations.size()).isEqualTo(2);
        assertThat(secondCitations.get(0).get("orgName").asString()).isEqualTo("poll-org-B");
        assertThat(secondCitations.get(1).get("orgName").asString()).isEqualTo("poll-org-A");
    }

    /** 폴링(afterId) 경로: 목록 안 메시지마다 자기 출처만, 목록 밖·다른 회원의 출처는 섞이지 않는다. */
    @Test
    void pollAttachesCitationsToEachMessageWithoutMixing() throws Exception {
        long chunkA = sourceChunk("poll-org-A", "A 운용사 간이투자설명서", "https://example.com/poll/a");
        long chunkB = sourceChunk("poll-org-B", "B 운용사 투자설명서", "https://example.com/poll/b");
        long chunkC = sourceChunk("poll-org-C", "C 운용사 투자설명서", "https://example.com/poll/c");
        long before = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "목록 밖 답변");
        cite(before, 1, chunkC);
        long others = insertMessage(otherMemberId, "ASSISTANT", "ANSWER", "COMPLETED", "남의 답변");
        cite(others, 1, chunkC);
        long first = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "첫 답변");
        cite(first, 1, chunkB);
        cite(first, 2, chunkA);
        long plain = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        long second = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "둘째 답변");
        cite(second, 1, chunkA);

        JsonNode body = poll(cookie, before);

        assertThat(ids(body)).containsExactly(first, plain, second);
        JsonNode firstCitations = messageOf(body, first).get("citations");
        assertThat(firstCitations.size()).isEqualTo(2);
        assertThat(firstCitations.get(0).get("orgName").asString()).isEqualTo("poll-org-B");
        assertThat(firstCitations.get(1).get("orgName").asString()).isEqualTo("poll-org-A");
        assertThat(messageOf(body, plain).get("citations").size()).isZero();
        JsonNode secondCitations = messageOf(body, second).get("citations");
        assertThat(secondCitations.size()).isEqualTo(1);
        assertThat(secondCitations.get(0).get("orgName").asString()).isEqualTo("poll-org-A");
        assertThat(body.toString()).doesNotContain("poll-org-C");
    }

    /** 문서 비활성화는 검색 제외용이다. 이미 COMPLETED 메시지에 연결된 출처는 복원·폴링 모두에서 그대로 나온다. */
    @Test
    void citationsOfDeactivatedDocumentsAreStillShown() throws Exception {
        long inactive = sourceChunk("poll-org-off", "비활성 운용사 투자설명서", "https://example.com/poll/inactive");
        long active = sourceChunk("poll-org-A", "A 운용사 간이투자설명서", "https://example.com/poll/a");
        jdbc.sql("UPDATE rag_document SET is_active = FALSE WHERE id = "
                        + "(SELECT document_id FROM rag_chunk WHERE id = ?)")
                .param(inactive).update();
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "ETF 위험은?");
        long answer = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "위험 설명");
        cite(answer, 1, inactive);
        cite(answer, 2, active);

        for (JsonNode body : List.of(poll(cookie, null), poll(cookie, question))) {
            JsonNode citations = messageOf(body, answer).get("citations");
            assertThat(citations.size()).isEqualTo(2);
            assertThat(citations.get(0).get("title").asString()).isEqualTo("비활성 운용사 투자설명서");
            assertThat(citations.get(0).get("sourceUrl").asString()).isEqualTo("https://example.com/poll/inactive");
            assertThat(citations.get(0).get("orgName").asString()).isEqualTo("poll-org-off");
            assertThat(citations.get(1).get("orgName").asString()).isEqualTo("poll-org-A");
        }
    }

    // ---------- AC-4 응답 모양: 정해진 키만 ----------

    @Test
    void responseHasOnlyContractKeys() throws Exception {
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        long calcRun = insertCalcRun(memberId, "PLAN");
        long recalc = jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, content, "
                        + "calc_run_id, baseline_scenario, condition_changes, retry_of_message_id) "
                        + "VALUES (?, 'ASSISTANT', 'RECALC_RESULT', 'COMPLETED', '재계산 결과', ?, 'AVG', "
                        + "CAST('{\"monthlySavingAmount\":500000}' AS jsonb), ?) RETURNING id")
                .params(memberId, calcRun, question).query(Long.class).single();
        long chunk = sourceChunk("poll-org-A", "A 운용사 간이투자설명서", "https://example.com/poll/a");
        cite(recalc, 1, chunk);
        long pending = insertMessage(memberId, "ASSISTANT", "ANSWER", "PENDING", null);

        JsonNode body = poll(cookie, null);

        assertThat(body.propertyNames()).containsExactlyInAnyOrder("messages", "hasPending", "answerPending");
        assertThat(ids(body)).containsExactly(question, recalc, pending);
        for (JsonNode message : body.get("messages")) {
            assertThat(message.propertyNames()).containsExactlyInAnyOrderElementsOf(MESSAGE_KEYS);
            assertThat(message.get("id").isIntegralNumber()).as("id는 JSON 정수").isTrue();
            assertThat(message.get("role").isString()).isTrue();
            assertThat(message.get("status").isString()).isTrue();
            assertThat(message.get("createdAt").isString()).isTrue();
            assertThat(message.get("citations").isArray()).isTrue();
        }
        assertThat(body.get("hasPending").isBoolean()).isTrue();
        assertThat(body.get("answerPending").isBoolean()).isTrue();
        JsonNode recalcMessage = messageOf(body, recalc);
        assertThat(recalcMessage.get("messageType").asString()).isEqualTo("RECALC_RESULT");
        assertThat(recalcMessage.get("citations").get(0).has("rank")).isFalse();
        assertThat(messageOf(body, question).get("citations").size()).isZero();
    }

    /**
     * 내부 필드(calc_run_id·baseline_scenario·condition_changes·retry_of_message_id)가 채워진 RECALC_RESULT를 복원과
     * 폴링(목록 앞·RECALC_RESULT부터) 모두로 받아도 최상위·메시지·출처 원소의 키 집합과 타입은 계약 그대로다.
     * 복원만 모양을 맞추고 폴링에서 conditionChanges·calcRunId 등을 더 싣는 구현은 여기서 걸린다.
     */
    @Test
    void pollResponseAlsoHasOnlyContractKeys() throws Exception {
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "월 50만원이면?");
        long calcRun = insertCalcRun(memberId, "PLAN");
        long recalc = jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, content, "
                        + "calc_run_id, baseline_scenario, condition_changes, retry_of_message_id) "
                        + "VALUES (?, 'ASSISTANT', 'RECALC_RESULT', 'COMPLETED', '재계산 결과', ?, 'AVG', "
                        + "CAST('{\"monthlySavingAmount\":500000}' AS jsonb), ?) RETURNING id")
                .params(memberId, calcRun, question).query(Long.class).single();
        cite(recalc, 2, sourceChunk("poll-org-B", "B 운용사 투자설명서", "https://example.com/poll/b"));
        cite(recalc, 1, sourceChunk("poll-org-A", "A 운용사 간이투자설명서", "https://example.com/poll/a"));
        long pending = insertMessage(memberId, "ASSISTANT", "ANSWER", "PENDING", null);

        for (String afterId : new String[] {null, String.valueOf(question - 1), String.valueOf(question)}) {
            String raw = getMessages(cookie, afterId).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            JsonNode body = JSON.readTree(raw);
            String path = "afterId=" + afterId;

            assertThat(body.propertyNames()).as(path + " 최상위 키")
                    .containsExactlyInAnyOrder("messages", "hasPending", "answerPending");
            assertThat(body.get("messages").isArray()).as(path).isTrue();
            assertThat(body.get("hasPending").isBoolean()).as(path).isTrue();
            assertThat(body.get("answerPending").isBoolean()).as(path).isTrue();
            assertThat(ids(body)).as(path).contains(recalc, pending);
            for (JsonNode message : body.get("messages")) {
                String where = path + " 메시지 " + message.get("id");
                assertThat(message.propertyNames()).as(where + " 키").containsExactlyInAnyOrderElementsOf(MESSAGE_KEYS);
                assertThat(message.get("id").isIntegralNumber()).as(where + " id는 JSON 정수").isTrue();
                assertThat(message.get("role").isString()).as(where).isTrue();
                assertThat(message.get("status").isString()).as(where).isTrue();
                assertThat(message.get("createdAt").isString()).as(where).isTrue();
                assertThat(message.get("messageType").isString() || message.get("messageType").isNull())
                        .as(where + " messageType은 문자열 또는 null").isTrue();
                assertThat(message.get("content").isString() || message.get("content").isNull())
                        .as(where + " content는 문자열 또는 null").isTrue();
                assertThat(message.get("citations").isArray()).as(where).isTrue();
                for (JsonNode citation : message.get("citations")) {
                    assertThat(citation.propertyNames()).as(where + " 출처 원소 키")
                            .containsExactlyInAnyOrder("title", "sourceUrl", "orgName");
                    assertThat(citation.get("title").isString()).as(where).isTrue();
                    assertThat(citation.get("sourceUrl").isString()).as(where).isTrue();
                    assertThat(citation.get("orgName").isString()).as(where).isTrue();
                }
            }
            JsonNode recalcMessage = messageOf(body, recalc);
            assertThat(recalcMessage.get("messageType").asString()).as(path).isEqualTo("RECALC_RESULT");
            assertThat(recalcMessage.get("content").asString()).as(path).isEqualTo("재계산 결과");
            assertThat(recalcMessage.get("citations").size()).as(path).isEqualTo(2);
            assertThat(recalcMessage.get("citations").get(0).get("orgName").asString()).as(path)
                    .isEqualTo("poll-org-A");
            assertThat(recalcMessage.get("citations").get(1).get("orgName").asString()).as(path)
                    .isEqualTo("poll-org-B");
            assertThat(raw).as(path + " 응답 어디에도 내부 필드가 없다")
                    .doesNotContain("conditionChanges", "calcRunId", "baselineScenario", "retryOfMessageId",
                            "\"rank\"", "monthlySavingAmount", "condition_changes", "calc_run_id");
        }
    }

    @Test
    void createdAtIsIso8601OfTheStoredInstant() throws Exception {
        Instant storedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(5));
        long question = insertMessageAt(memberId, "USER", "QUESTION", "COMPLETED", "질문", storedAt);

        String createdAt = messageOf(poll(cookie, null), question).get("createdAt").asString();

        assertThat(OffsetDateTime.parse(createdAt).toInstant()).isEqualTo(storedAt);
    }

    /** DB가 저장한 마이크로초까지 같은 시각이어야 한다(초 단위로 잘리지 않음). */
    @Test
    void createdAtKeepsStoredFractionalSeconds() throws Exception {
        Instant storedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(3))
                .plus(123_456, ChronoUnit.MICROS);
        long question = insertMessageAt(memberId, "USER", "QUESTION", "COMPLETED", "질문", storedAt);

        for (JsonNode body : List.of(poll(cookie, null), poll(cookie, question - 1))) {
            String createdAt = messageOf(body, question).get("createdAt").asString();
            assertThat(OffsetDateTime.parse(createdAt).toInstant()).isEqualTo(storedAt);
        }
    }

    // ---------- AC-5 복원(afterId 없음): 최근 50개, id 오름차순 ----------

    @Test
    void restoreReturnsLatest50InAscendingOrder() throws Exception {
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            mine.add(insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문 " + i));
            insertMessage(otherMemberId, "USER", "QUESTION", "COMPLETED", "남의 질문 " + i);
        }

        JsonNode body = poll(cookie, null);

        assertThat(ids(body)).containsExactlyElementsOf(mine.subList(5, 55));
    }

    /** "최근"은 id 기준이다. created_at이 id와 반대 순서여도 가장 큰 id 50개를 고른다. */
    @Test
    void restoreSelectsByIdNotByCreatedAt() throws Exception {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            mine.add(insertMessageAt(memberId, "USER", "QUESTION", "COMPLETED", "질문 " + i,
                    base.minus(Duration.ofMinutes(i))));
        }

        assertThat(ids(poll(cookie, null))).containsExactlyElementsOf(mine.subList(5, 55));
    }

    @Test
    void restoreWithExactly50ReturnsAll() throws Exception {
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            mine.add(insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문 " + i));
        }

        assertThat(ids(poll(cookie, null))).containsExactlyElementsOf(mine);
    }

    @Test
    void restoreWithNoMessagesReturnsEmptyAndNoPending() throws Exception {
        JsonNode body = poll(cookie, null);

        assertThat(body.get("messages").isArray()).isTrue();
        assertThat(body.get("messages").size()).isZero();
        assertThat(body.get("hasPending").asBoolean()).isFalse();
        assertThat(body.get("answerPending").asBoolean()).isFalse();
    }

    // ---------- AC-6 폴링(afterId=N): id > N 전부, 오름차순, 개수 제한 없음 ----------

    @Test
    void pollReturnsAllOwnMessagesAfterIdAscendingWithoutLimit() throws Exception {
        long before = insertQuestions(memberId, 3);
        List<Long> after = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            after.add(insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "새 질문 " + i));
            insertMessage(otherMemberId, "USER", "QUESTION", "COMPLETED", "남의 새 질문 " + i);
        }

        assertThat(ids(poll(cookie, before))).containsExactlyElementsOf(after);
    }

    /** 커서 뒤 자기 메시지가 100개를 넘어도(여기서는 201개) 상한 없이 전부 온다. 다른 회원 메시지는 섞이지 않는다. */
    @Test
    void pollReturnsMoreThan100OwnMessagesAfterCursorWithoutCap() throws Exception {
        long before = insertQuestions(memberId, 3);
        List<Long> after = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            after.add(insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "많은 새 질문 " + i));
            if (i % 2 == 0) {
                insertMessage(otherMemberId, "USER", "QUESTION", "COMPLETED", "남의 많은 질문 " + i);
            }
        }

        List<Long> polled = ids(poll(cookie, before));

        assertThat(polled).hasSize(201);
        assertThat(polled).containsExactlyElementsOf(after);
    }

    @Test
    void pollWithLatestIdReturnsNothingAndZeroReturnsEverything() throws Exception {
        long first = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문 1");
        long last = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "답변 1");

        assertThat(ids(poll(cookie, last))).isEmpty();
        assertThat(ids(poll(cookie, 0L))).containsExactly(first, last);
        assertThat(ids(poll(cookie, -1L))).containsExactly(first, last);
    }

    /** id는 BIGSERIAL이라 32비트를 넘는 커서도 정수다. */
    @Test
    void pollAcceptsCursorBeyond32Bits() throws Exception {
        long small = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "작은 id");
        long big = 5_000_000_001L;
        jdbc.sql("INSERT INTO chat_message (id, member_id, role, message_type, status, content) VALUES "
                        + "(?, ?, 'USER', 'QUESTION', 'COMPLETED', '큰 id 1'), "
                        + "(?, ?, 'USER', 'QUESTION', 'COMPLETED', '큰 id 2'), "
                        + "(?, ?, 'USER', 'QUESTION', 'COMPLETED', '남의 큰 id')")
                .params(big, memberId, big + 1, memberId, big + 2, otherMemberId).update();

        assertThat(ids(poll(cookie, big))).containsExactly(big + 1);
        JsonNode polled = poll(cookie, 3_000_000_000L);
        assertThat(ids(polled)).containsExactly(big, big + 1);
        assertThat(ids(poll(cookie, big + 1))).isEmpty();
        JsonNode restored = poll(cookie, null);
        assertThat(ids(restored)).containsExactly(small, big, big + 1);

        // 64비트 id는 복원·폴링 모두 문자열·실수가 아니라 JSON 정수로, 값 그대로 나간다
        JsonNode bigNode = restored.get("messages").get(1).get("id");
        assertThat(bigNode.isIntegralNumber()).isTrue();
        assertThat(bigNode.asLong()).isEqualTo(big);
        assertThat(restored.toString()).contains("\"id\":" + big);
        for (int i = 0; i < 2; i++) {
            JsonNode polledNode = polled.get("messages").get(i).get("id");
            assertThat(polledNode.isIntegralNumber()).as("폴링 응답 id는 JSON 정수: " + polledNode).isTrue();
            assertThat(polledNode.asLong()).isEqualTo(big + i);
            assertThat(polled.toString()).contains("\"id\":" + (big + i));
        }
    }

    /** 커서는 64비트 끝값까지 정수다. id > Long.MAX_VALUE는 없고, id > Long.MIN_VALUE는 전부다(덧셈 넘침 없음). */
    @Test
    void pollAcceptsLongMaxAndMinCursors() throws Exception {
        long first = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문 1");
        long second = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "답변 1");

        JsonNode max = poll(cookie, Long.MAX_VALUE);
        assertThat(max.get("messages").isArray()).isTrue();
        assertThat(ids(max)).isEmpty();
        assertThat(ids(poll(cookie, Long.MIN_VALUE))).containsExactly(first, second);
    }

    /** 프론트 규칙: afterId = 가장 작은 PENDING id − 1. 같은 메시지를 다시 받아 덮어쓰는 흐름. */
    @Test
    void repollFromPendingSeesItCompletedWithCitations() throws Exception {
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "ETF 위험은?");
        long pending = insertMessage(memberId, "ASSISTANT", "ANSWER", "PENDING", null);

        JsonNode first = poll(cookie, question);
        assertThat(ids(first)).containsExactly(pending);
        assertThat(messageOf(first, pending).get("status").asString()).isEqualTo("PENDING");
        assertThat(first.get("hasPending").asBoolean()).isTrue();

        long chunk = sourceChunk("poll-org-A", "A 운용사 간이투자설명서", "https://example.com/poll/a");
        jdbc.sql("UPDATE chat_message SET status = 'COMPLETED', content = '위험 설명' WHERE id = ?")
                .param(pending).update();
        cite(pending, 1, chunk);

        JsonNode second = poll(cookie, pending - 1);
        JsonNode completed = messageOf(second, pending);
        assertThat(completed.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(completed.get("messageType").asString()).isEqualTo("ANSWER");
        assertThat(completed.get("content").asString()).isEqualTo("위험 설명");
        assertThat(completed.get("citations").get(0).get("title").asString()).isEqualTo("A 운용사 간이투자설명서");
        assertThat(second.get("hasPending").asBoolean()).isFalse();
        assertThat(second.get("answerPending").asBoolean()).isFalse();
    }

    /** F-1로 실제 질문을 보내고 ai-server 답이 오기 전후를 폴링한다. */
    @Test
    void pollFollowsRealQuestionFromPendingToCompleted() throws Exception {
        long before = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "이전 질문");
        JsonNode accepted = JSON.readTree(askText(cookie, "월 50만원이면?")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        long userId = accepted.get("userMessageId").asLong();
        long assistantId = accepted.get("assistantMessageId").asLong();

        JsonNode waiting = poll(cookie, before);
        assertThat(ids(waiting)).containsExactly(userId, assistantId);
        assertThat(messageOf(waiting, assistantId).get("messageType").isNull()).isTrue();
        assertThat(waiting.get("answerPending").asBoolean()).isTrue();

        awaitAiRequests(1);
        AI.release(200, AiServerStub.ANSWER_OK);
        awaitStatus(assistantId, "COMPLETED");

        JsonNode done = poll(cookie, assistantId - 1);
        assertThat(messageOf(done, assistantId).get("content").asString()).isEqualTo("스텁 답변");
        assertThat(messageOf(done, assistantId).get("messageType").asString()).isEqualTo("ANSWER");
        assertThat(done.get("hasPending").asBoolean()).isFalse();
        assertThat(done.get("answerPending").asBoolean()).isFalse();
    }

    // ---------- AC-7 다른 회원의 메시지는 보이지 않는다 ----------

    @Test
    void otherMembersMessagesAreNeverShown() throws Exception {
        long mine = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "내 질문");
        long others = insertMessage(otherMemberId, "USER", "QUESTION", "COMPLETED", "남의 질문");
        insertMessage(otherMemberId, "ASSISTANT", "ANSWER", "PENDING", null);

        JsonNode restored = poll(cookie, null);
        assertThat(ids(restored)).containsExactly(mine);
        assertThat(restored.get("hasPending").asBoolean()).isFalse();
        assertThat(restored.get("answerPending").asBoolean()).isFalse();

        assertThat(ids(poll(cookie, 0L))).containsExactly(mine);
        assertThat(ids(poll(otherCookie, null))).doesNotContain(mine).contains(others);
    }

    /** 폴링(afterId) 경로에서도 다른 회원의 질문·설명 대기는 두 플래그에 섞이지 않는다. */
    @Test
    void othersPendingDoesNotAffectFlagsWhenPolling() throws Exception {
        long mine = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "내 질문");
        insertMessage(otherMemberId, "ASSISTANT", "ANSWER", "PENDING", null);
        long othersRun = insertCalcRun(otherMemberId, "PLAN");
        insertExplanation(otherMemberId, "PENDING", null, othersRun, "AVG", Instant.now());

        for (long afterId : new long[] {0L, mine - 1, mine}) {
            JsonNode body = poll(cookie, afterId);
            assertThat(body.get("hasPending").asBoolean()).isFalse();
            assertThat(body.get("answerPending").asBoolean()).isFalse();
        }
        JsonNode others = poll(otherCookie, 0L);
        assertThat(others.get("hasPending").asBoolean()).isTrue();
        assertThat(others.get("answerPending").asBoolean()).isTrue();
    }

    @Test
    void afterIdOfOtherMembersMessageReturnsOnlyOwnMessages() throws Exception {
        long mineBefore = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "내 질문 1");
        long others = insertMessage(otherMemberId, "USER", "QUESTION", "COMPLETED", "남의 질문");
        long othersLater = insertMessage(otherMemberId, "ASSISTANT", "ANSWER", "COMPLETED", "남의 답변");
        long mineAfter = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "내 질문 2");

        JsonNode body = poll(cookie, others);

        assertThat(ids(body)).containsExactly(mineAfter).doesNotContain(mineBefore, others, othersLater);
    }

    // ---------- AC-8 hasPending·answerPending ----------

    @Test
    void answerPendingMakesBothFlagsTrue() throws Exception {
        insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        insertMessage(memberId, "ASSISTANT", "ANSWER", "PENDING", null);

        JsonNode body = poll(cookie, null);

        assertThat(body.get("hasPending").asBoolean()).isTrue();
        assertThat(body.get("answerPending").asBoolean()).isTrue();
        assertThat(body.get("hasPending").isBoolean()).isTrue();
        assertThat(body.get("answerPending").isBoolean()).isTrue();
    }

    @Test
    void explanationPendingOnlyIsHasPendingWithoutAnswerPending() throws Exception {
        long calcRun = insertCalcRun(memberId, "PLAN");
        insertExplanation(memberId, "PENDING", null, calcRun, "AVG", Instant.now());
        insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "끝난 답변");

        JsonNode body = poll(cookie, null);

        assertThat(body.get("hasPending").asBoolean()).isTrue();
        assertThat(body.get("answerPending").asBoolean()).isFalse();
    }

    @Test
    void pendingOutsideTheListStillCountsForFlags() throws Exception {
        long pending = insertMessage(memberId, "ASSISTANT", "ANSWER", "PENDING", null);
        long latest = insertQuestions(memberId, 3);

        JsonNode polled = poll(cookie, latest);
        assertThat(ids(polled)).isEmpty();
        assertThat(polled.get("hasPending").asBoolean()).isTrue();
        assertThat(polled.get("answerPending").asBoolean()).isTrue();

        insertQuestions(memberId, 55);
        JsonNode restored = poll(cookie, null);
        assertThat(ids(restored)).hasSize(50).doesNotContain(pending);
        assertThat(restored.get("hasPending").asBoolean()).isTrue();
        assertThat(restored.get("answerPending").asBoolean()).isTrue();
    }

    /** 목록 밖에 설명 대기(EXPLANATION)만 남았으면 hasPending만 true다. */
    @Test
    void explanationPendingOutsideTheListIsHasPendingOnly() throws Exception {
        long calcRun = insertCalcRun(memberId, "PLAN");
        long pending = insertExplanation(memberId, "PENDING", null, calcRun, "AVG", Instant.now());
        long latest = insertQuestions(memberId, 3);

        JsonNode polled = poll(cookie, latest);
        assertThat(ids(polled)).isEmpty();
        assertThat(polled.get("hasPending").asBoolean()).isTrue();
        assertThat(polled.get("answerPending").asBoolean()).isFalse();

        insertQuestions(memberId, 55);
        JsonNode restored = poll(cookie, null);
        assertThat(ids(restored)).hasSize(50).doesNotContain(pending);
        assertThat(restored.get("hasPending").asBoolean()).isTrue();
        assertThat(restored.get("answerPending").asBoolean()).isFalse();
    }

    @Test
    void noPendingMeansBothFlagsFalse() throws Exception {
        insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "답변");
        insertMessage(memberId, "ASSISTANT", "FAILURE", "FAILED", failedSentence());

        JsonNode body = poll(cookie, null);

        assertThat(body.get("hasPending").asBoolean()).isFalse();
        assertThat(body.get("answerPending").asBoolean()).isFalse();
    }

    // ---------- AC-9 오래된 PENDING은 먼저 정리하고 조회한다 ----------

    @Test
    void stalePendingIsFailedBeforeQueryAndShownAsFailure() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long staleAnswer = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(61)));
        long calcRun = insertCalcRun(memberId, "PLAN");
        long staleExplanation = insertExplanation(memberId, "PENDING", null, calcRun, "AVG",
                now.minus(Duration.ofSeconds(90)));

        JsonNode body = poll(cookie, null);

        for (long id : List.of(staleAnswer, staleExplanation)) {
            JsonNode message = messageOf(body, id);
            assertThat(message.get("status").asString()).isEqualTo("FAILED");
            assertThat(message.get("messageType").asString()).isEqualTo("FAILURE");
            assertThat(message.get("content").asString()).isEqualTo(failedSentence());
        }
        assertThat(body.get("hasPending").asBoolean()).isFalse();
        assertThat(body.get("answerPending").asBoolean()).isFalse();
    }

    /** 정리는 저장된다(읽기 전용 트랜잭션이면 UPDATE가 실패하거나 남지 않는다). */
    @Test
    void staleCleanupIsPersisted() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long stale = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(61)));

        poll(cookie, null);

        Map<String, Object> cleaned = row(stale);
        assertThat(cleaned.get("status")).isEqualTo("FAILED");
        assertThat(cleaned.get("message_type")).isEqualTo("FAILURE");
        assertThat(cleaned.get("content")).isEqualTo(failedSentence());
    }

    @Test
    void stalePendingOutsideTheListIsAlsoCleaned() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long stale = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(61)));
        long latest = insertQuestions(memberId, 2);

        JsonNode body = poll(cookie, latest);

        assertThat(ids(body)).isEmpty();
        assertThat(body.get("hasPending").asBoolean()).isFalse();
        assertThat(body.get("answerPending").asBoolean()).isFalse();
        assertThat(row(stale).get("status")).isEqualTo("FAILED");
    }

    /**
     * 복원 경로에서도 정리 대상은 응답 범위와 무관하게 그 회원의 오래된 PENDING 전부다.
     * 오래된 행이 최근 50개 밖(더 작은 id)에 있어도 DB에서 정리되고 대기 플래그에 잡히지 않는다.
     */
    @Test
    void restoreCleansStalePendingOutsideTheLatest50() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long staleAnswer = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(61)));
        long calcRun = insertCalcRun(memberId, "PLAN");
        long staleExplanation = insertExplanation(memberId, "PENDING", null, calcRun, "AVG",
                now.minus(Duration.ofSeconds(90)));
        List<Long> latest = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            latest.add(insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문 " + i));
        }

        JsonNode body = poll(cookie, null);

        assertThat(ids(body)).containsExactlyElementsOf(latest);
        assertThat(ids(body)).doesNotContain(staleAnswer, staleExplanation);
        assertThat(body.get("hasPending").asBoolean()).isFalse();
        assertThat(body.get("answerPending").asBoolean()).isFalse();
        for (long stale : List.of(staleAnswer, staleExplanation)) {
            Map<String, Object> cleaned = row(stale);
            assertThat(cleaned.get("status")).isEqualTo("FAILED");
            assertThat(cleaned.get("message_type")).isEqualTo("FAILURE");
            assertThat(cleaned.get("content")).isEqualTo(failedSentence());
        }
    }

    @Test
    void recentPendingIsKeptAndOthersStalePendingIsUntouched() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long recent = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(59)));
        long boundary = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(60)));
        long othersStale = insertMessageAt(otherMemberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(120)));

        JsonNode body = poll(cookie, null);

        assertThat(messageOf(body, recent).get("status").asString()).isEqualTo("PENDING");
        assertThat(messageOf(body, boundary).get("status").asString()).isEqualTo("PENDING");
        assertThat(body.get("hasPending").asBoolean()).isTrue();
        assertThat(body.get("answerPending").asBoolean()).isTrue();
        assertThat(row(othersStale).get("status")).isEqualTo("PENDING");
    }

    /** 폴링 경로에서도 정리는 요청 회원의 오래된 PENDING만 바꾼다. 다른 회원의 오래된 PENDING은 커서 앞뒤 어디에 있어도 DB에 그대로 남는다. */
    @Test
    void pollCleansOnlyOwnStalePendingAndLeavesOthersUntouched() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long othersStaleBefore = insertMessageAt(otherMemberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(120)));
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        long ownStale = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(61)));
        long othersStaleAfter = insertMessageAt(otherMemberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(90)));
        long othersCalcRun = insertCalcRun(otherMemberId, "PLAN");
        long othersStaleExplanation = insertExplanation(otherMemberId, "PENDING", null, othersCalcRun, "AVG",
                now.minus(Duration.ofSeconds(300)));

        JsonNode body = poll(cookie, question);

        assertThat(ids(body)).containsExactly(ownStale);
        JsonNode cleaned = messageOf(body, ownStale);
        assertThat(cleaned.get("status").asString()).isEqualTo("FAILED");
        assertThat(cleaned.get("messageType").asString()).isEqualTo("FAILURE");
        assertThat(body.get("hasPending").asBoolean()).isFalse();
        assertThat(body.get("answerPending").asBoolean()).isFalse();
        assertThat(row(ownStale).get("status")).isEqualTo("FAILED");
        for (long others : List.of(othersStaleBefore, othersStaleAfter)) {
            Map<String, Object> untouched = row(others);
            assertThat(untouched.get("status")).isEqualTo("PENDING");
            assertThat(untouched.get("message_type")).isEqualTo("ANSWER");
            assertThat(untouched.get("content")).isNull();
        }
        Map<String, Object> untouchedExplanation = row(othersStaleExplanation);
        assertThat(untouchedExplanation.get("status")).isEqualTo("PENDING");
        assertThat(untouchedExplanation.get("message_type")).isEqualTo("EXPLANATION");
        assertThat(untouchedExplanation.get("content")).isNull();
    }

    /** 폴링 경로에서도 자기 회원의 오래된 설명 대기(EXPLANATION)를 질문 답변 대기와 똑같이 정리한다. */
    @Test
    void pollCleansOwnStaleExplanationAfterCursor() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        long calcRun = insertCalcRun(memberId, "PLAN");
        long staleExplanation = insertExplanation(memberId, "PENDING", null, calcRun, "AVG",
                now.minus(Duration.ofSeconds(61)));

        JsonNode body = poll(cookie, question);

        assertThat(ids(body)).containsExactly(staleExplanation);
        JsonNode cleaned = messageOf(body, staleExplanation);
        assertThat(cleaned.get("status").asString()).isEqualTo("FAILED");
        assertThat(cleaned.get("messageType").asString()).isEqualTo("FAILURE");
        assertThat(cleaned.get("content").asString()).isEqualTo(failedSentence());
        assertThat(body.get("hasPending").asBoolean()).isFalse();
        assertThat(body.get("answerPending").asBoolean()).isFalse();
        Map<String, Object> stored = row(staleExplanation);
        assertThat(stored.get("status")).isEqualTo("FAILED");
        assertThat(stored.get("message_type")).isEqualTo("FAILURE");
        assertThat(stored.get("content")).isEqualTo(failedSentence());
    }

    /** 60초를 1마이크로초만 넘겨도 정리된다(폴링 경로). 정확히 60초는 남는다. */
    @Test
    void pendingJustOver60SecondsIsCleanedWhenPolling() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock.fixAt(now);
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        long justOver = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                now.minus(Duration.ofSeconds(60)).minus(1, ChronoUnit.MICROS));
        long calcRun = insertCalcRun(memberId, "PLAN");
        long exact = insertExplanation(memberId, "PENDING", null, calcRun, "AVG", now.minus(Duration.ofSeconds(60)));

        JsonNode body = poll(cookie, question);

        JsonNode cleaned = messageOf(body, justOver);
        assertThat(cleaned.get("status").asString()).isEqualTo("FAILED");
        assertThat(cleaned.get("messageType").asString()).isEqualTo("FAILURE");
        assertThat(cleaned.get("content").asString()).isEqualTo(failedSentence());
        assertThat(messageOf(body, exact).get("status").asString()).isEqualTo("PENDING");
        assertThat(body.get("hasPending").asBoolean()).isTrue();
        assertThat(body.get("answerPending").asBoolean()).isFalse();
        assertThat(row(justOver).get("status")).isEqualTo("FAILED");
    }

    // ---------- AC-10 afterId 형식 오류 ----------

    @Test
    void nonIntegerAfterIdReturns400Malformed() throws Exception {
        insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        for (String afterId : List.of("abc", "1.5", "12a", "1e3", "true", "1,2", "-", "1 2", "null")) {
            getMessages(cookie, afterId)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.errors").isArray())
                    .andExpect(jsonPath("$.errors").isEmpty());
        }
    }

    /**
     * `afterId=`(값이 빈 쿼리)는 파라미터 생략(복원)이 아니다. 빈 값·공백은 정수가 아니므로 400이다.
     * 같은 상태에서 파라미터를 생략하면 200 복원이다.
     */
    @Test
    void emptyAfterIdIsMalformedNotRestore() throws Exception {
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        for (String afterId : List.of("", " ")) {
            getMessages(cookie, afterId)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.errors").isArray())
                    .andExpect(jsonPath("$.errors").isEmpty());
        }

        assertThat(ids(poll(cookie, null))).containsExactly(question);
    }

    // ---------- AC-11 토큰 만료를 늘리지 않는다 ----------

    @Test
    void pollingNeverRenewsTokenCookie() throws Exception {
        clock.fixAt(Instant.now().minus(Duration.ofMinutes(29)));   // 29분 전에 로그인해 1분 남은 토큰
        Cookie nearlyExpired = login("demo01");
        clock.useSystem();
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");

        for (String afterId : new String[] {null, String.valueOf(question - 1), String.valueOf(question)}) {
            getMessages(nearlyExpired, afterId)
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        }
    }

    /** 실제 F-1 질문 뒤 PENDING을 1초 폴링하듯 여러 번 불러도 새 토큰 쿠키가 없다. */
    @Test
    void pollingRealPendingAnswerNeverRenewsTokenCookie() throws Exception {
        long assistantId = askAndGetAssistantId(cookie, "월 50만원이면?");

        for (int i = 0; i < 3; i++) {
            getMessages(cookie, String.valueOf(assistantId - 1))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                    .andExpect(jsonPath("$.answerPending").value(true));
        }
        getMessages(cookie, null)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    /** 대조: 사용자 조작(F-1)은 같은 토큰으로 새 쿠키를 받는다. 폴링만 연장에서 빠진다. */
    @Test
    void questionStillRenewsTokenCookieWhilePollingDoesNot() throws Exception {
        getMessages(cookie, null)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        List<String> cookies = askText(cookie, "질문").andReturn().getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).anySatisfy(value -> assertThat(value).startsWith(JwtTokenService.COOKIE_NAME + "="));
    }

    // ---------- AC-12 인증 ----------

    @Test
    void withoutLoginReturns401() throws Exception {
        getMessages(null, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        getMessages(null, "0")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** 30분 넘게 폴링만 한 사용자의 토큰(31분 전 발급)은 만료되어 401이다. */
    @Test
    void tokenIssued31MinutesAgoReturns401() throws Exception {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(31)), ZoneId.of("UTC"));
        String expired = new JwtTokenService(jwtProperties, past).issue(memberId);
        insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");

        getMessages(new Cookie(JwtTokenService.COOKIE_NAME, expired), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** 폴링 경로(afterId 있음)도 만료 토큰이면 401이고, 정리 UPDATE를 하지 않는다. */
    @Test
    void expiredTokenOnPollingPathReturns401WithoutCleanup() throws Exception {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(31)), ZoneId.of("UTC"));
        String expired = new JwtTokenService(jwtProperties, past).issue(memberId);
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        long stale = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                Instant.now().minus(Duration.ofMinutes(5)));

        getMessages(new Cookie(JwtTokenService.COOKIE_NAME, expired), String.valueOf(question - 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        getMessages(new Cookie(JwtTokenService.COOKIE_NAME, expired), "abc")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(row(stale).get("status")).isEqualTo("PENDING");
    }
}
