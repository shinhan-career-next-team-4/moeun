package com.moeun.backend.chat.infrastructure;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.moeun.backend.chat.domain.ChatMessage;
import com.moeun.backend.chat.domain.ChatMessageRepository;
import com.moeun.backend.chat.domain.HistoryRow;
import com.moeun.backend.chat.domain.MessageRow;
import com.moeun.backend.chat.domain.MessageStatus;
import com.moeun.backend.chat.domain.MessageTransition;
import com.moeun.backend.chat.domain.MessageType;
import com.moeun.backend.chat.domain.QuestionMessage;
import com.moeun.backend.chat.domain.RecalcResultRow;
import com.moeun.backend.chat.domain.Role;

/**
 * {@link ChatMessageRepository}의 JdbcClient 구현. 상태 전이의 값은 도메인({@link ChatMessage})이 정하고, 여기서는
 * 행이 아직 전이 전 상태일 때만 반영하는 조건부 UPDATE로 동시성을 지킨다.
 */
@Repository
class JdbcChatMessageRepository implements ChatMessageRepository {

    private static final RowMapper<MessageRow> MESSAGE_ROW = (rs, row) -> new MessageRow(rs.getLong("id"),
            Role.valueOf(rs.getString("role")), MessageType.valueOf(rs.getString("message_type")),
            MessageStatus.valueOf(rs.getString("status")), rs.getString("content"),
            rs.getObject("calc_run_id", Long.class), rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    JdbcChatMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insertQuestion(long memberId, String text) {
        return jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, content) "
                        + "VALUES (?, 'USER', 'QUESTION', 'COMPLETED', ?) RETURNING id")
                .params(memberId, text).query(Long.class).single();
    }

    @Override
    public long insertPendingAnswer(long memberId) {
        return jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status) "
                        + "VALUES (?, 'ASSISTANT', 'ANSWER', 'PENDING') RETURNING id")
                .param(memberId).query(Long.class).single();
    }

    /**
     * F-3: 설명 대기 행. 기준 시나리오는 요청 시점 값을 저장해 두고 리스너가 그 값으로 EXPLAIN을 보낸다.
     * retryOfMessageId는 '설명 다시 요청'의 원본(FR-AI-05)이고 다시 요청이 아니면 null이다.
     */
    @Override
    public long insertPendingExplanation(long memberId, long calcRunId, String baselineScenario,
            Long retryOfMessageId) {
        return jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, calc_run_id, "
                        + "baseline_scenario, retry_of_message_id) "
                        + "VALUES (?, 'ASSISTANT', 'EXPLANATION', 'PENDING', ?, ?, ?) RETURNING id")
                .params(memberId, calcRunId, baselineScenario, retryOfMessageId).query(Long.class).single();
    }

    /**
     * 그 회원의 설명 행인지(FR-AI-05의 다시 요청 원본). 완료·대기 설명은 EXPLANATION이고, 실패한 설명은 계산 기록을
     * 가진 FAILURE다(질문 답변은 실패해도 계산 기록이 없다). 없는·남의·설명이 아닌 행은 모두 false다.
     */
    @Override
    public boolean existsExplanationOf(long id, long memberId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat_message WHERE id = ? AND member_id = ? "
                        + "AND role = 'ASSISTANT' AND calc_run_id IS NOT NULL "
                        + "AND message_type IN ('EXPLANATION', 'FAILURE'))")
                .params(id, memberId).query(Boolean.class).single();
    }

    /** 그 회원이 같은 기록으로 이미 요청한 설명이 PENDING인지. 동시 요청으로 여러 행일 수 있어 존재 여부만 본다. */
    @Override
    public boolean existsPendingExplanation(long memberId, long calcRunId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat_message WHERE member_id = ? AND calc_run_id = ? "
                        + "AND message_type = 'EXPLANATION' AND status = 'PENDING')")
                .params(memberId, calcRunId).query(Boolean.class).single();
    }

    @Override
    public boolean existsPendingAnswer(long memberId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat_message "
                        + "WHERE member_id = ? AND message_type = 'ANSWER' AND status = 'PENDING')")
                .param(memberId).query(Boolean.class).single();
    }

    /** 그 회원의 PENDING(질문·설명 모두)이 하나라도 있는지. 목록 밖의 오래된 id도 잡힌다. */
    @Override
    public boolean existsPending(long memberId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat_message WHERE member_id = ? AND status = 'PENDING')")
                .param(memberId).query(Boolean.class).single();
    }

    /** 복원: 최근 limit개를 id 오름차순으로(키셋 DESC LIMIT 뒤 뒤집기). */
    @Override
    public List<MessageRow> findLatest(long memberId, int limit) {
        List<MessageRow> rows = jdbc.sql("SELECT id, role, message_type, status, content, calc_run_id, created_at "
                        + "FROM chat_message WHERE member_id = ? ORDER BY id DESC LIMIT ?")
                .params(memberId, limit).query(MESSAGE_ROW).list();
        Collections.reverse(rows);
        return rows;
    }

    /** 폴링: afterId보다 큰 id의 자기 메시지 전부를 id 오름차순으로. */
    @Override
    public List<MessageRow> findAfter(long memberId, long afterId) {
        return jdbc.sql("SELECT id, role, message_type, status, content, calc_run_id, created_at "
                        + "FROM chat_message WHERE member_id = ? AND id > ? ORDER BY id")
                .params(memberId, afterId).query(MESSAGE_ROW).list();
    }

    @Override
    public int failPendingCreatedBefore(long memberId, OffsetDateTime createdBefore, String failedContent) {
        // 행마다 ChatMessage.fail과 같은 전이다(계산 기록·기준 시나리오는 남긴다). 여러 행을 한 문장으로 바꾼다
        return jdbc.sql("UPDATE chat_message SET status = ?, message_type = ?, content = ? "
                        + "WHERE member_id = ? AND status = ? AND created_at < ?")
                .params(MessageStatus.FAILED.name(), MessageType.FAILURE.name(), failedContent, memberId,
                        MessageStatus.PENDING.name(), createdBefore).update();
    }

    @Override
    public Optional<ChatMessage> findPending(long id, long memberId) {
        return jdbc.sql("SELECT id, member_id, message_type, status, calc_run_id, baseline_scenario "
                        + "FROM chat_message WHERE id = ? AND member_id = ? AND status = 'PENDING'")
                .params(id, memberId)
                .query((rs, row) -> new ChatMessage(rs.getLong("id"), rs.getLong("member_id"),
                        MessageType.valueOf(rs.getString("message_type")),
                        MessageStatus.valueOf(rs.getString("status")), rs.getObject("calc_run_id", Long.class),
                        rs.getString("baseline_scenario")))
                .optional();
    }

    /** F-4: 그 회원의 COMPLETED RECALC_RESULT 한 행. 없는·남의·다른 종류·COMPLETED가 아닌 행은 모두 빈 값이다. */
    @Override
    public Optional<RecalcResultRow> findCompletedRecalcResult(long id, long memberId) {
        return jdbc.sql("SELECT id, calc_run_id, condition_changes::text AS condition_changes, "
                        + "baseline_condition_changes::text AS baseline_condition_changes FROM chat_message "
                        + "WHERE id = ? AND member_id = ? AND message_type = 'RECALC_RESULT' AND status = 'COMPLETED'")
                .params(id, memberId)
                .query((rs, row) -> new RecalcResultRow(rs.getLong("id"), rs.getLong("calc_run_id"),
                        rs.getString("condition_changes"), rs.getString("baseline_condition_changes")))
                .optional();
    }

    /** F-4 applicable 판정 ①: 그 회원의 COMPLETED RECALC_RESULT 중 id가 가장 큰 행. */
    @Override
    public Optional<Long> findLatestCompletedRecalcResultId(long memberId) {
        return jdbc.sql("SELECT max(id) FROM chat_message "
                        + "WHERE member_id = ? AND message_type = 'RECALC_RESULT' AND status = 'COMPLETED'")
                .param(memberId).query(Long.class).optional();
    }

    /** beforeId보다 앞서 저장된 가장 최근 질문(답변 행 바로 앞에 저장된 USER 행). */
    @Override
    public Optional<QuestionMessage> findLatestQuestionBefore(long memberId, long beforeId) {
        return jdbc.sql("SELECT id, content FROM chat_message "
                        + "WHERE member_id = ? AND role = 'USER' AND id < ? ORDER BY id DESC LIMIT 1")
                .params(memberId, beforeId)
                .query((rs, row) -> new QuestionMessage(rs.getLong("id"), rs.getString("content")))
                .optional();
    }

    /** beforeId보다 앞선 COMPLETED 메시지 중 최근 limit개를 오래된 것부터. */
    @Override
    public List<HistoryRow> findCompletedBefore(long memberId, long beforeId, int limit) {
        return jdbc.sql("SELECT role, message_type, content FROM (SELECT id, role, message_type, content "
                        + "FROM chat_message WHERE member_id = ? AND status = 'COMPLETED' AND id < ? "
                        + "ORDER BY id DESC LIMIT ?) recent ORDER BY id")
                .params(memberId, beforeId, limit)
                .query((rs, row) -> new HistoryRow(Role.valueOf(rs.getString("role")),
                        MessageType.valueOf(rs.getString("message_type")), rs.getString("content")))
                .list();
    }

    @Override
    public int apply(MessageTransition transition) {
        return jdbc.sql("UPDATE chat_message SET status = ?, message_type = ?, content = ?, calc_run_id = ?, "
                        + "baseline_scenario = ?, condition_changes = CAST(? AS jsonb), "
                        + "baseline_condition_changes = CAST(? AS jsonb) "
                        + "WHERE id = ? AND member_id = ? AND status = ?")
                .params(transition.status().name(), transition.type().name(), transition.content(),
                        transition.calcRunId(), transition.baselineScenario(), transition.conditionChangesJson(),
                        transition.baselineConditionChangesJson(),
                        transition.id(), transition.memberId(), transition.from().name())
                .update();
    }

    /** 인용 chunk를 순서(rank 1부터)대로 저장한다. 그 회원의 메시지일 때만 저장한다. */
    @Override
    public void insertCitations(long memberId, long messageId, List<Long> chunkIds) {
        int rank = 1;
        for (Long chunkId : chunkIds) {
            jdbc.sql("INSERT INTO chat_message_citation (message_id, rank, chunk_id) "
                            + "SELECT id, ?, ? FROM chat_message WHERE id = ? AND member_id = ?")
                    .params(rank++, chunkId, messageId, memberId).update();
        }
    }

    /**
     * 그 회원의 대화를 한 문장으로 전부 지운다(#46). 행을 하나씩 지우면 retry_of_message_id(NO ACTION)가 문장마다
     * 검사되어 순서에 따라 실패하므로 일괄 DELETE다. chat_message_citation은 FK CASCADE로 함께 지워진다.
     */
    @Override
    public int deleteAllOf(long memberId) {
        return jdbc.sql("DELETE FROM chat_message WHERE member_id = ?").param(memberId).update();
    }
}
