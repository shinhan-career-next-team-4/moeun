package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.auth.DemoReset;

/** 데모 초기화: 그 회원의 대화와 계획(plan, plan_version, calc_run)만 지우고 다른 회원과 계정은 남긴다. */
class DemoResetTest extends ChatIntegrationTestSupport {

    private void publishInTransaction(Object event) {
        transactions.executeWithoutResult(status -> publisher.publishEvent(event));
    }

    /** 계획 한 벌(버전, calc_run, 현재 계획 포인터)과 그 calc_run을 참조하는 대화. */
    private void storePlanAndConversation(long member) {
        long calcRunId = insertCalcRun(member, "PLAN");
        long versionId = jdbc.sql("SELECT plan_version_id FROM calc_run WHERE id = ?").param(calcRunId)
                .query(Long.class).single();
        jdbc.sql("INSERT INTO plan (member_id, current_version_id, baseline_scenario, revision) "
                        + "VALUES (?, ?, 'AVG', 1)")
                .params(member, versionId).update();
        insertMessage(member, "USER", "QUESTION", "COMPLETED", "질문");
        jdbc.sql("UPDATE chat_message SET calc_run_id = ? WHERE member_id = ?").params(calcRunId, member).update();
    }

    private long count(String table, long member) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE member_id = ?").param(member)
                .query(Long.class).single();
    }

    private long calcRunCount(long member) {
        return jdbc.sql("SELECT count(*) FROM calc_run r JOIN plan_version v ON v.id = r.plan_version_id "
                        + "WHERE v.member_id = ?").param(member).query(Long.class).single();
    }

    @Test
    @DisplayName("데모 초기화 이벤트를 받으면 그 회원의 대화·계획·계산 기록이 모두 지워지고 계정은 남는다")
    void demoResetDeletesPlanAndConversation() {
        storePlanAndConversation(memberId);

        publishInTransaction(new DemoReset(memberId));

        assertThat(count("chat_message", memberId)).isZero();
        assertThat(count("plan", memberId)).isZero();
        assertThat(count("plan_version", memberId)).isZero();
        assertThat(calcRunCount(memberId)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM member WHERE id = ?").param(memberId).query(Long.class).single())
                .as("계정은 남는다").isEqualTo(1);
    }

    @Test
    @DisplayName("다른 회원의 계획과 대화는 그대로 남는다")
    void demoResetKeepsOtherMembersData() {
        storePlanAndConversation(memberId);
        storePlanAndConversation(otherMemberId);

        publishInTransaction(new DemoReset(memberId));

        assertThat(count("chat_message", otherMemberId)).isEqualTo(1);
        assertThat(count("plan", otherMemberId)).isEqualTo(1);
        assertThat(count("plan_version", otherMemberId)).isEqualTo(1);
        assertThat(calcRunCount(otherMemberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 받아도 결과가 같다")
    void demoResetIsIdempotent() {
        storePlanAndConversation(memberId);

        publishInTransaction(new DemoReset(memberId));
        publishInTransaction(new DemoReset(memberId));

        assertThat(count("plan", memberId)).isZero();
        assertThat(count("chat_message", memberId)).isZero();
    }
}
