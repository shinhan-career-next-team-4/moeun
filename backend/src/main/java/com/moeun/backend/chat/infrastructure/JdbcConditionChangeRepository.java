package com.moeun.backend.chat.infrastructure;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.moeun.backend.chat.domain.ConditionChange;
import com.moeun.backend.chat.domain.ConditionChangeRepository;

/** {@link ConditionChangeRepository}의 JdbcClient 구현. 값은 jsonb로 저장하고 문자열로 읽는다. */
@Repository
class JdbcConditionChangeRepository implements ConditionChangeRepository {

    private final JdbcClient jdbc;

    JdbcConditionChangeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ConditionChange> findAll(long memberId, long planVersionId) {
        return jdbc.sql("SELECT field, before_value::text AS before_value, after_value::text AS after_value, "
                        + "message_id FROM chat_condition_change WHERE member_id = ? AND plan_version_id = ? "
                        + "ORDER BY id")
                .params(memberId, planVersionId)
                .query((rs, row) -> new ConditionChange(rs.getString("field"), rs.getString("before_value"),
                        rs.getString("after_value"), rs.getLong("message_id")))
                .list();
    }

    @Override
    public void append(long memberId, long messageId, long planVersionId, List<ConditionChange> changes) {
        for (ConditionChange change : changes) {
            jdbc.sql("INSERT INTO chat_condition_change (member_id, message_id, plan_version_id, field, before_value, "
                            + "after_value) VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))")
                    .params(memberId, messageId, planVersionId, change.field(), change.beforeJson(),
                            change.afterJson())
                    .update();
        }
    }

    @Override
    public int deleteAllOf(long memberId) {
        return jdbc.sql("DELETE FROM chat_condition_change WHERE member_id = ?").param(memberId).update();
    }

    @Override
    public int deleteOfMessage(long memberId, long messageId) {
        return jdbc.sql("DELETE FROM chat_condition_change WHERE member_id = ? AND message_id = ?")
                .params(memberId, messageId).update();
    }

    @Override
    public int deleteField(long memberId, long planVersionId, String field) {
        return jdbc.sql("DELETE FROM chat_condition_change WHERE member_id = ? AND plan_version_id = ? AND field = ?")
                .params(memberId, planVersionId, field).update();
    }
}
