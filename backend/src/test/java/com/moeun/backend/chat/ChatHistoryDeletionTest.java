package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.ReflectionUtils;

import com.moeun.backend.auth.MemberLoggedIn;
import com.moeun.backend.auth.MemberLoggedOut;

import jakarta.servlet.http.Cookie;

/** 로그인·로그아웃 때 이전 대화 삭제(#46, Q-041 권장안 A, 계약 C12b-8). */
class ChatHistoryDeletionTest extends ChatIntegrationTestSupport {

    private static final String ANSWER_WITH_CITATIONS = """
            {"messageType":"ANSWER","content":"늦은 답변","baselineScenario":"AVG","calcRunId":null,
             "conditionChanges":null,"citationChunkIds":%s}""";

    /** 발행 쪽 트랜잭션 안에서 이벤트를 발행한다. 동기 리스너면 이 호출이 반환될 때 삭제가 끝나 있어야 한다. */
    private void publishInTransaction(Object event) {
        transactions.executeWithoutResult(status -> publisher.publishEvent(event));
    }

    private long storeConversation(long member) {
        insertMessage(member, "USER", "QUESTION", "COMPLETED", "질문");
        return insertMessage(member, "ASSISTANT", "ANSWER", "COMPLETED", "답변");
    }

    private long citationCount() {
        return jdbc.sql("SELECT count(*) FROM chat_message_citation").query(Long.class).single();
    }

    // ---------- 로그인 ----------

    @Test
    @DisplayName("이전 대화가 있는 회원이 로그인 이벤트를 받으면 그 회원의 chat_message가 없다")
    void loggedInEventDeletesOwnMessages() {
        storeConversation(memberId);
        storeConversation(memberId);
        assertThat(countRows(memberId)).isEqualTo(4);

        publishInTransaction(new MemberLoggedIn(memberId));

        assertThat(countRows(memberId)).isZero();
    }

    @Test
    @DisplayName("실제 로그인 요청이 끝나면 이전 대화가 이미 지워져 있다")
    void realLoginDeletesPreviousConversationBeforeResponse() throws Exception {
        storeConversation(memberId);

        login("demo01");

        assertThat(countRows(memberId)).isZero();
    }

    @Test
    @DisplayName("내가 로그인해도 다른 회원의 대화는 지워지지 않는다")
    void loggedInEventKeepsOtherMembersMessages() {
        storeConversation(memberId);
        storeConversation(otherMemberId);

        publishInTransaction(new MemberLoggedIn(memberId));

        assertThat(countRows(memberId)).isZero();
        assertThat(countRows(otherMemberId)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 도착해도 결과가 같다")
    void deletionIsIdempotent() {
        storeConversation(memberId);
        storeConversation(otherMemberId);

        publishInTransaction(new MemberLoggedIn(memberId));
        publishInTransaction(new MemberLoggedIn(memberId));
        publishInTransaction(new MemberLoggedOut(memberId));

        assertThat(countRows(memberId)).isZero();
        assertThat(countRows(otherMemberId)).isEqualTo(2);
    }

    // ---------- 로그아웃 ----------

    @Test
    @DisplayName("로그아웃 이벤트를 받으면 chat_message와 chat_message_citation이 함께 지워진다")
    void loggedOutEventDeletesMessagesAndCitations() {
        List<Long> chunks = insertChunks();
        long mine = storeConversation(memberId);
        long theirs = storeConversation(otherMemberId);
        jdbc.sql("INSERT INTO chat_message_citation (message_id, rank, chunk_id) VALUES (?, 1, ?)")
                .params(mine, chunks.get(0)).update();
        jdbc.sql("INSERT INTO chat_message_citation (message_id, rank, chunk_id) VALUES (?, 1, ?)")
                .params(theirs, chunks.get(0)).update();
        assertThat(citationCount()).isEqualTo(2);

        publishInTransaction(new MemberLoggedOut(memberId));

        assertThat(countRows(memberId)).isZero();
        assertThat(countRows(otherMemberId)).isEqualTo(2);
        assertThat(citationCount()).as("남의 citation만 남는다").isEqualTo(1);
    }

    // ---------- 동기 @EventListener ----------

    @Test
    @DisplayName("리스너는 @ApplicationModuleListener가 아닌 동기 @EventListener이고 자체 @Transactional이다")
    void listenersAreSynchronousEventListenersWithOwnTransaction() {
        for (Class<?> event : List.of(MemberLoggedIn.class, MemberLoggedOut.class)) {
            List<Method> listeners = listenersOf(event);

            assertThat(listeners).as(event.getSimpleName() + "를 받는 chat 리스너").hasSize(1);
            Method listener = listeners.get(0);
            assertThat(AnnotatedElementUtils.hasAnnotation(listener, ApplicationModuleListener.class))
                    .as("@ApplicationModuleListener 금지").isFalse();
            assertThat(AnnotatedElementUtils.hasAnnotation(listener, TransactionalEventListener.class))
                    .as("@TransactionalEventListener 금지(커밋 후 수신이 되면 응답 전 삭제를 보장하지 못한다)").isFalse();
            assertThat(AnnotatedElementUtils.hasAnnotation(listener, Async.class)).as("@Async 금지").isFalse();
            assertThat(AnnotatedElementUtils.findMergedAnnotation(listener, EventListener.class)).isNotNull();
            assertThat(AnnotatedElementUtils.hasAnnotation(listener, Transactional.class)
                    || AnnotatedElementUtils.hasAnnotation(listener.getDeclaringClass(), Transactional.class))
                    .as("자체 @Transactional").isTrue();
        }
    }

    @Test
    @DisplayName("동기 수신은 event_publication에 남지 않는다")
    void deletionLeavesNoEventPublication() {
        storeConversation(memberId);

        publishInTransaction(new MemberLoggedIn(memberId));
        publishInTransaction(new MemberLoggedOut(memberId));

        assertThat(jdbc.sql("SELECT count(*) FROM event_publication WHERE event_type LIKE '%MemberLogged%'")
                .query(Long.class).single()).isZero();
    }

    private List<Method> listenersOf(Class<?> event) {
        List<Method> found = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type == null || !type.getName().startsWith("com.moeun.backend.chat.")) {
                continue;
            }
            Class<?> userClass = AopUtils.getTargetClass(context.getBean(name));
            ReflectionUtils.doWithMethods(userClass, method -> {
                if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == event
                        && AnnotatedElementUtils.hasAnnotation(method, EventListener.class)) {
                    found.add(method);
                }
            });
        }
        return found;
    }

    // ---------- 예외: retry_of 자기·상호 참조 ----------

    @Test
    @DisplayName("retry_of_message_id로 서로 참조하는 메시지도 순서와 상관없이 전부 지워진다")
    void mutuallyReferencingMessagesAreDeleted() {
        long first = insertMessage(memberId, "ASSISTANT", "FAILURE", "FAILED", "실패 1");
        long second = insertMessage(memberId, "ASSISTANT", "FAILURE", "FAILED", "실패 2");
        long selfRef = insertMessage(memberId, "ASSISTANT", "FAILURE", "FAILED", "실패 3");
        jdbc.sql("UPDATE chat_message SET retry_of_message_id = ? WHERE id = ?").params(second, first).update();
        jdbc.sql("UPDATE chat_message SET retry_of_message_id = ? WHERE id = ?").params(first, second).update();
        jdbc.sql("UPDATE chat_message SET retry_of_message_id = ? WHERE id = ?").params(selfRef, selfRef).update();

        publishInTransaction(new MemberLoggedOut(memberId));

        assertThat(countRows(memberId)).isZero();
    }

    // ---------- D-057: calc_run(CHAT)과 계획은 남는다 ----------

    @Test
    @DisplayName("삭제가 끝나도 calc_run(CHAT)과 계획(plan, plan_version)은 남아 있다")
    void calcRunsAndPlansSurviveDeletion() {
        long calcRun = insertCalcRun(memberId, "CHAT");
        long planVersion = jdbc.sql("SELECT plan_version_id FROM calc_run WHERE id = ?").param(calcRun)
                .query(Long.class).single();
        jdbc.sql("INSERT INTO plan (member_id, current_version_id, baseline_scenario) VALUES (?, ?, 'AVG')")
                .params(memberId, planVersion).update();
        jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, content, calc_run_id, "
                        + "baseline_scenario) VALUES (?, 'ASSISTANT', 'EXPLANATION', 'COMPLETED', '설명', ?, 'AVG')")
                .params(memberId, calcRun).update();

        try {
            publishInTransaction(new MemberLoggedOut(memberId));

            assertThat(countRows(memberId)).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM calc_run WHERE id = ? AND run_type = 'CHAT'")
                    .param(calcRun).query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM plan_version WHERE id = ?").param(planVersion)
                    .query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM plan WHERE member_id = ?").param(memberId)
                    .query(Long.class).single()).isEqualTo(1);
        } finally {
            jdbc.sql("DELETE FROM chat_message").update();
            jdbc.sql("DELETE FROM plan WHERE member_id = ?").param(memberId).update();
            jdbc.sql("DELETE FROM calc_run WHERE id = ?").param(calcRun).update();
            jdbc.sql("DELETE FROM plan_version WHERE id = ?").param(planVersion).update();
        }
    }

    // ---------- 예외: 삭제 실패는 삼키지 않는다 ----------

    @Test
    @DisplayName("삭제 중 예외가 나면 이벤트를 발행한 쪽으로 올라가 로그인·로그아웃이 실패한다")
    void deletionFailurePropagatesToPublisher() throws Exception {
        storeConversation(memberId);
        jdbc.sql("CREATE OR REPLACE FUNCTION fail_chat_delete() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'chat delete blocked by test'; END; $$ LANGUAGE plpgsql").update();
        jdbc.sql("CREATE TRIGGER trg_fail_chat_delete BEFORE DELETE ON chat_message "
                + "FOR EACH STATEMENT EXECUTE FUNCTION fail_chat_delete()").update();
        try {
            assertThatThrownBy(() -> publishInTransaction(new MemberLoggedIn(memberId)))
                    .hasStackTraceContaining("chat delete blocked by test");
            assertThatThrownBy(() -> publishInTransaction(new MemberLoggedOut(memberId)))
                    .hasStackTraceContaining("chat delete blocked by test");

            Cookie issued = null;
            try {
                issued = login("demo01");
            } catch (Exception expected) {
                // 예외로 올라오는 것도 요청 실패다
            }
            assertThat(issued).as("삭제가 실패하면 로그인 쿠키가 발급되지 않는다").isNull();
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS trg_fail_chat_delete ON chat_message").update();
            jdbc.sql("DROP FUNCTION IF EXISTS fail_chat_delete()").update();
        }
        assertThat(countRows(memberId)).as("실패한 삭제는 롤백된다").isEqualTo(2);
    }

    // ---------- 예외(H-2): 삭제 뒤 늦게 온 ai 응답 ----------

    @Test
    @DisplayName("PENDING이 있는 채로 로그아웃해 삭제된 뒤 ai 응답이 오면 버려지고 citation도 event_publication 잔여도 없다")
    void lateAnswerAfterLogoutIsDiscarded() throws Exception {
        List<Long> chunks = insertChunks();
        AI.hold();
        long assistantId = askAndGetAssistantId(cookie, "로그아웃 직전 질문");
        awaitAiRequests(1);
        assertThat(statusOf(assistantId)).isEqualTo("PENDING");

        publishInTransaction(new MemberLoggedOut(memberId));
        assertThat(countRows(memberId)).isZero();

        AI.release(200, ANSWER_WITH_CITATIONS.formatted(chunks));
        awaitAnswerEventsCompleted();

        assertThat(countRows(memberId)).as("늦은 응답이 행을 되살리지 않는다").isZero();
        assertThat(citationCount()).as("citation이 저장되지 않는다").isZero();
        assertThat(incompleteAnswerEvents()).as("event_publication에 미완료가 남지 않는다").isZero();
    }
}
