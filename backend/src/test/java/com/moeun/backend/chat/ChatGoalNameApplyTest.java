package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.PlanSaveResult;

import tools.jackson.databind.JsonNode;

/**
 * 대화에서 목표 이름을 바꾼 비교의 조회·적용(#345). 결과 화면에서 이름을 고칠 때(#341)와 같은 규칙이다: 적용하면 목표 종류가
 * 직접 입력(CUSTOM)이 되고 이름은 앞뒤 공백을 뗀다(D-081). 다른 조건과 계산 결과는 함께 바꾼 것만 달라진다.
 */
class ChatGoalNameApplyTest extends ChatComparisonTestSupport {

    private static final ConditionOverride NO_NUMBER_CHANGE = new ConditionOverride(null, null, null, null, null,
            null);

    private ResultActions apply(long messageId, long expectedRevision) throws Exception {
        return mockMvc.perform(post("/api/chat/messages/{id}/apply", messageId).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":" + expectedRevision + "}"));
    }

    private JsonNode comparisonBody(long messageId) throws Exception {
        return json.readTree(comparison(cookie, messageId).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private Map<String, Object> currentGoal() {
        return jdbc.sql("SELECT v.goal_type_code, v.goal_custom_name, v.goal_amount, v.monthly_saving_amount "
                + "FROM plan p JOIN plan_version v ON v.id = p.current_version_id WHERE p.member_id = ?")
                .param(memberId).query().singleRow();
    }

    private long revision() {
        return jdbc.sql("SELECT revision FROM plan WHERE member_id = ?").param(memberId).query(Long.class).single();
    }

    private long versionCount() {
        return jdbc.sql("SELECT count(*) FROM plan_version WHERE member_id = ?").param(memberId).query(Long.class)
                .single();
    }

    @Test
    @DisplayName("이름만 바꾼 가장 최근 비교는 F-4 applicable이 true이고 changedKeys와 goalCustomName에 바꿀 이름이 있다")
    void showsGoalNameComparison() throws Exception {
        savePlan(memberId, null, 300_000L);
        long messageId = recalc(memberId, NO_NUMBER_CHANGE, "{\"goalCustomName\":\"가게 보증금\"}").messageId();

        JsonNode body = comparisonBody(messageId);

        assertThat(body.get("applicable").asBoolean()).isTrue();
        assertThat(body.get("changedKeys").get(0).asString()).isEqualTo("goalCustomName");
        assertThat(body.get("goalCustomName").asString()).isEqualTo("가게 보증금");
    }

    @Test
    @DisplayName("이름을 바꾸지 않은 비교의 F-4 응답에는 goalCustomName 키가 없다")
    void omitsGoalNameWhenNotChanged() throws Exception {
        savePlan(memberId, null, 300_000L);
        long messageId = recalc(memberId, new ConditionOverride(500_000L, null, null, null, null, null),
                "{\"monthlySavingAmount\":500000}").messageId();

        assertThat(comparisonBody(messageId).has("goalCustomName")).isFalse();
    }

    @Test
    @DisplayName("이름만 바꾼 비교를 F-5로 적용하면 목표가 직접 입력(CUSTOM)과 그 이름이 되고 다른 조건은 그대로이며, "
            + "되돌리면 원래 목표로 돌아간다")
    void appliesGoalNameAndUndoRestoresIt() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        long messageId = recalc(memberId, NO_NUMBER_CHANGE, "{\"goalCustomName\":\"  가게 보증금 \"}").messageId();

        apply(messageId, saved.revision()).andExpect(status().isOk());
        awaitEventsCompleted();

        Map<String, Object> goal = currentGoal();
        assertThat(goal.get("goal_type_code")).isEqualTo("CUSTOM");
        assertThat(goal.get("goal_custom_name")).isEqualTo("가게 보증금");
        assertThat(((Number) goal.get("goal_amount")).longValue()).isEqualTo(20_000_000L);
        assertThat(((Number) goal.get("monthly_saving_amount")).longValue()).isEqualTo(300_000L);

        mockMvc.perform(post("/api/plan/undo").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision() + "}"))
                .andExpect(status().isOk());
        awaitEventsCompleted();

        assertThat(currentGoal().get("goal_type_code")).isEqualTo("HOUSE");
        assertThat(currentGoal().get("goal_custom_name")).isNull();
    }

    @Test
    @DisplayName("이름과 목표 금액을 함께 바꾼 비교를 적용하면 둘 다 저장된다")
    void appliesGoalNameWithGoalAmount() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        long messageId = recalc(memberId, new ConditionOverride(null, null, null, null, 50_000_000L, null),
                "{\"goalCustomName\":\"가게 보증금\",\"goalAmount\":50000000}").messageId();

        apply(messageId, saved.revision()).andExpect(status().isOk());

        Map<String, Object> goal = currentGoal();
        assertThat(goal.get("goal_type_code")).isEqualTo("CUSTOM");
        assertThat(goal.get("goal_custom_name")).isEqualTo("가게 보증금");
        assertThat(((Number) goal.get("goal_amount")).longValue()).isEqualTo(50_000_000L);
    }

    @Test
    @DisplayName("(예외) 저장된 이름이 공백뿐이면 F-5는 422 VALIDATION_FAILED(goalCustomName, OUT_OF_RANGE)이고 계획은 "
            + "바뀌지 않는다")
    void rejectsBlankGoalName() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        long messageId = recalc(memberId, NO_NUMBER_CHANGE, "{\"goalCustomName\":\"   \"}").messageId();

        apply(messageId, saved.revision()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("goalCustomName"))
                .andExpect(jsonPath("$.errors[0].reason").value("OUT_OF_RANGE"));
        assertThat(versionCount()).isEqualTo(1);
        assertThat(revision()).isEqualTo(saved.revision());
    }
}
