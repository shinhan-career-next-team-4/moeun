package com.moeun.backend.chat.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * chat_message·chat_message_citation 접근. 모든 메서드가 memberId 조건을 받는다(소유권은 DB가 막지 않는다).
 * 상태 갱신은 {@code status = 'PENDING'}일 때만 하고 바뀐 행 수를 돌려준다. 0행이면 그 사이 삭제·정리된 것이다.
 */
@Repository
public class ChatMessageRepository {

    private final JdbcClient jdbc;

    ChatMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insertQuestion(long memberId, String text) {
        return jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, content) "
                        + "VALUES (?, 'USER', 'QUESTION', 'COMPLETED', ?) RETURNING id")
                .params(memberId, text).query(Long.class).single();
    }

    public long insertPendingAnswer(long memberId) {
        return jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status) "
                        + "VALUES (?, 'ASSISTANT', 'ANSWER', 'PENDING') RETURNING id")
                .param(memberId).query(Long.class).single();
    }

    public boolean existsPendingAnswer(long memberId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat_message "
                        + "WHERE member_id = ? AND message_type = 'ANSWER' AND status = 'PENDING')")
                .param(memberId).query(Boolean.class).single();
    }

    /** 그 회원의 PENDING 중 createdBefore보다 먼저 만들어진 행을 FAILED로 바꾼다. 경계(같은 시각)는 건드리지 않는다. */
    public int failPendingCreatedBefore(long memberId, OffsetDateTime createdBefore, String failedContent) {
        return jdbc.sql("UPDATE chat_message SET status = 'FAILED', message_type = 'FAILURE', content = ? "
                        + "WHERE member_id = ? AND status = 'PENDING' AND created_at < ?")
                .params(failedContent, memberId, createdBefore).update();
    }

    public Optional<PendingMessage> findPending(long id, long memberId) {
        return jdbc.sql("SELECT id, member_id, message_type, calc_run_id, baseline_scenario FROM chat_message "
                        + "WHERE id = ? AND member_id = ? AND status = 'PENDING'")
                .params(id, memberId)
                .query((rs, row) -> new PendingMessage(rs.getLong("id"), rs.getLong("member_id"),
                        rs.getString("message_type"), rs.getObject("calc_run_id", Long.class),
                        rs.getString("baseline_scenario")))
                .optional();
    }

    /** beforeId보다 앞서 저장된 가장 최근 질문(답변 행 바로 앞에 저장된 USER 행). */
    public Optional<QuestionMessage> findLatestQuestionBefore(long memberId, long beforeId) {
        return jdbc.sql("SELECT id, content FROM chat_message "
                        + "WHERE member_id = ? AND role = 'USER' AND id < ? ORDER BY id DESC LIMIT 1")
                .params(memberId, beforeId)
                .query((rs, row) -> new QuestionMessage(rs.getLong("id"), rs.getString("content")))
                .optional();
    }

    /** beforeId보다 앞선 COMPLETED 메시지 중 최근 limit개를 오래된 것부터. */
    public List<HistoryMessage> findCompletedBefore(long memberId, long beforeId, int limit) {
        return jdbc.sql("SELECT role, content FROM (SELECT id, role, content FROM chat_message "
                        + "WHERE member_id = ? AND status = 'COMPLETED' AND id < ? ORDER BY id DESC LIMIT ?) recent "
                        + "ORDER BY id")
                .params(memberId, beforeId, limit)
                .query((rs, row) -> new HistoryMessage(rs.getString("role").toLowerCase(Locale.ROOT),
                        rs.getString("content")))
                .list();
    }

    /**
     * 응답이 null로 비워 둔 calcRunId·baselineScenario는 행에 이미 있는 값을 유지한다.
     * 단 OUT_OF_SCOPE는 계산 기록을 가리키지 않으므로(ck_cm_out_of_scope) calc_run_id를 NULL로 비운다.
     */
    public int complete(long id, long memberId, String messageType, String content, Long calcRunId,
            String baselineScenario, String conditionChangesJson) {
        return jdbc.sql("UPDATE chat_message SET status = 'COMPLETED', message_type = ?, content = ?, "
                        + "calc_run_id = CASE WHEN ? = 'OUT_OF_SCOPE' THEN NULL ELSE COALESCE(?, calc_run_id) END, "
                        + "baseline_scenario = COALESCE(?, baseline_scenario), "
                        + "condition_changes = CAST(? AS jsonb) "
                        + "WHERE id = ? AND member_id = ? AND status = 'PENDING'")
                .params(messageType, content, messageType, calcRunId, baselineScenario, conditionChangesJson, id,
                        memberId)
                .update();
    }

    public int fail(long id, long memberId, String failedContent) {
        return jdbc.sql("UPDATE chat_message SET status = 'FAILED', message_type = 'FAILURE', content = ? "
                        + "WHERE id = ? AND member_id = ? AND status = 'PENDING'")
                .params(failedContent, id, memberId).update();
    }

    /** 인용 chunk를 순서(rank 1부터)대로 저장한다. 그 회원의 메시지일 때만 저장한다. */
    public void insertCitations(long memberId, long messageId, List<Long> chunkIds) {
        int rank = 1;
        for (Long chunkId : chunkIds) {
            jdbc.sql("INSERT INTO chat_message_citation (message_id, rank, chunk_id) "
                            + "SELECT id, ?, ? FROM chat_message WHERE id = ? AND member_id = ?")
                    .params(rank++, chunkId, messageId, memberId).update();
        }
    }
}
