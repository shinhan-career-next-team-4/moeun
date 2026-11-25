package com.moeun.backend.chat;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.moeun.backend.plan.PlanView;

import tools.jackson.databind.JsonNode;

/**
 * 대화 조건 누적(#418, D-103). 대화에서 바꾼 조건은 적용하지 않아도 다음 질문의 비교 기준이 된다. 누적 상태는 최종 값이 아니라
 * 변경 목록(조건·이전 값·바꾼 값·순서)으로 저장하고, 내 계획으로 적용하거나 대화 세션을 끝내면 비운다.
 */
class ChatConditionAccumulationTest extends ChatAccumulationTestSupport {

    @Test
    @DisplayName("비중을 30:70으로 바꾼 뒤 다음 질문의 ai-server 요청에 그 변경(이전 값 50 → 30)이 실린다")
    void sendsAccumulatedChangesToAiServer() throws Exception {
        JsonNode first = recalcTurn("적금 30으로 바꾸면?", Map.of(), Map.of("savingsRatioPct", 30));
        assertThat(first.path("accumulatedChanges")).isEmpty();

        JsonNode second = recalcTurn("월 150만원으로 늘리면?", Map.of("savingsRatioPct", 30),
                Map.of("monthlySavingAmount", 1_500_000));

        JsonNode entry = second.get("accumulatedChanges").get(0);
        assertThat(entry.get("field").asString()).isEqualTo("savingsRatioPct");
        assertThat(entry.get("before").asInt()).isEqualTo(50);
        assertThat(entry.get("after").asInt()).isEqualTo(30);
        assertThat(second.get("accumulatedChanges")).hasSize(1);
    }

    @Test
    @DisplayName("같은 조건을 두 번 바꾸면(150만 → 200만) 목록에 순서대로 두 항목이 남고 마지막 값은 200만이다")
    void keepsBothChangesOfSameField() throws Exception {
        recalcTurn("월 150만원으로", Map.of(), Map.of("monthlySavingAmount", 1_500_000));
        recalcTurn("월 200만원으로", Map.of("monthlySavingAmount", 1_500_000), Map.of("monthlySavingAmount", 2_000_000));

        JsonNode third = askWithAnswer("고마워요", AiServerStub.ANSWER_OK);

        JsonNode list = third.get("accumulatedChanges");
        assertThat(fields(third)).containsExactly("monthlySavingAmount", "monthlySavingAmount");
        assertThat(list.get(0).get("before").asLong()).isEqualTo(300_000L);
        assertThat(list.get(0).get("after").asLong()).isEqualTo(1_500_000L);
        assertThat(list.get(1).get("before").asLong()).isEqualTo(1_500_000L);
        assertThat(list.get(1).get("after").asLong()).isEqualTo(2_000_000L);
    }

    @Test
    @DisplayName("조건 3개를 연속으로 바꾸면 세 번째 F-4의 비교 기준은 두 번째까지 누적된 계획이고 쌓인 조건은 셋이다")
    void thirdComparisonUsesSecondAccumulatedPlan() throws Exception {
        recalcTurn("적금 30으로", Map.of(), Map.of("savingsRatioPct", 30));
        recalcTurn("월 150만원으로", Map.of("savingsRatioPct", 30), Map.of("monthlySavingAmount", 1_500_000));
        recalcTurn("기간 4년으로", Map.of("savingsRatioPct", 30, "monthlySavingAmount", 1_500_000),
                Map.of("targetPeriodMonths", 48));

        JsonNode body = JSON.readTree(mockMvc.perform(get("/api/chat/messages/{id}/comparison", latestRecalcId())
                        .cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode current = body.get("conditions").get("current");
        assertThat(current.get("savingsRatioPct").asInt()).isEqualTo(30);
        assertThat(current.get("monthlySavingAmount").asLong()).isEqualTo(1_500_000L);
        assertThat(current.get("targetPeriodMonths").asInt()).isEqualTo(36);
        assertThat(body.get("conditions").get("changed").get("targetPeriodMonths").asInt()).isEqualTo(48);
        assertThat(body.get("changedKeys")).extracting(JsonNode::asString).containsExactly("targetPeriodMonths");
        assertThat(body.get("accumulatedKeys")).extracting(JsonNode::asString)
                .containsExactlyInAnyOrder("savingsRatioPct", "monthlySavingAmount", "targetPeriodMonths");
        assertThat(body.get("applicable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("내 계획으로 적용하면 쌓인 조건이 새 계획이 되고 변경 목록이 빈다")
    void applyMakesAccumulatedPlanAndClearsList() throws Exception {
        recalcTurn("적금 30으로", Map.of(), Map.of("savingsRatioPct", 30));
        recalcTurn("월 150만원으로", Map.of("savingsRatioPct", 30), Map.of("monthlySavingAmount", 1_500_000));
        long revision = planSaveService.findCurrent(memberId).orElseThrow().revision();

        mockMvc.perform(post("/api/chat/messages/{id}/apply", latestRecalcId()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":" + revision + "}"))
                .andExpect(status().isOk());

        PlanView plan = planSaveService.findCurrent(memberId).orElseThrow();
        assertThat(plan.savingsRatioPct()).isEqualTo(30);
        assertThat(plan.monthlySavingAmount()).isEqualTo(1_500_000L);
        JsonNode next = askWithAnswer("이제 어때요?", AiServerStub.ANSWER_OK);
        assertThat(next.path("accumulatedChanges")).isEmpty();
    }

    @Test
    @DisplayName("대화 세션을 끝내면(DELETE /api/chat/condition-changes) 변경 목록이 비고 다음 질문은 내 계획 기준이다")
    void clearingEndsAccumulation() throws Exception {
        recalcTurn("적금 30으로", Map.of(), Map.of("savingsRatioPct", 30));

        mockMvc.perform(delete("/api/chat/condition-changes").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accumulatedKeys").isEmpty());

        JsonNode next = askWithAnswer("월 150만원이면?", AiServerStub.ANSWER_OK);
        assertThat(next.path("accumulatedChanges")).isEmpty();
    }

    @Test
    @DisplayName("변경 목록 비우기는 다른 회원의 목록을 건드리지 않고, 로그인하지 않으면 401이다")
    void clearingIsPerMemberAndNeedsLogin() throws Exception {
        recalcTurn("적금 30으로", Map.of(), Map.of("savingsRatioPct", 30));

        mockMvc.perform(delete("/api/chat/condition-changes").cookie(otherCookie)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/chat/condition-changes")).andExpect(status().isUnauthorized());

        JsonNode next = askWithAnswer("다음은?", AiServerStub.ANSWER_OK);
        assertThat(fields(next)).containsExactly("savingsRatioPct");
    }

    @Test
    @DisplayName("다시 로그인하면(대화 세션 새로 시작) 대화와 함께 변경 목록도 지워진다")
    void loginClearsList() throws Exception {
        recalcTurn("적금 30으로", Map.of(), Map.of("savingsRatioPct", 30));

        cookie = login("demo01");

        assertThat(jdbc.sql("SELECT count(*) FROM chat_condition_change WHERE member_id = ?").param(memberId)
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("(예외) 계산 엔진 검증에 걸려 되묻기(CLARIFY)로 끝난 질문은 변경 목록에 추가하지 않는다")
    void clarifyDoesNotAppend() throws Exception {
        recalcTurn("적금 30으로", Map.of(), Map.of("savingsRatioPct", 30));

        askWithAnswer("월 10억원으로", CLARIFY);
        JsonNode next = askWithAnswer("그럼 뭐가 가능해요?", AiServerStub.ANSWER_OK);

        assertThat(fields(next)).containsExactly("savingsRatioPct");
    }

    @Test
    @DisplayName("같은 값을 다시 말한 변경(30 → 30)은 목록에 더하지 않는다")
    void sameValueIsNotAppended() throws Exception {
        recalcTurn("적금 30으로", Map.of(), Map.of("savingsRatioPct", 30));
        recalcTurn("적금 30으로 다시", Map.of("savingsRatioPct", 30), Map.of("savingsRatioPct", 30));

        JsonNode next = askWithAnswer("다음은?", AiServerStub.ANSWER_OK);
        assertThat(fields(next)).containsExactly("savingsRatioPct");
    }

    @Test
    @DisplayName("(예외) 긴 대화여도 대화 원문은 최근 10개만 보내고, 이전 계산 결과 글은 금액 없는 안내로 바꾼다")
    void historyIsRecentTenWithoutResultAmounts() throws Exception {
        recalcTurn("월 150만원으로", Map.of(), Map.of("monthlySavingAmount", 1_500_000));
        for (int turn = 0; turn < 5; turn++) {
            askWithAnswer("질문 " + turn, AiServerStub.ANSWER_OK);
        }

        JsonNode last = askWithAnswer("마지막 질문", AiServerStub.ANSWER_OK);

        JsonNode history = last.get("history");
        assertThat(history).hasSize(10);
        List<String> contents = new ArrayList<>();
        history.forEach(item -> contents.add(item.get("content").asString()));
        assertThat(contents).doesNotContain("계산했어요");

        JsonNode withRecalc = askHistoryIncludingRecalc();
        assertThat(withRecalc.toString()).doesNotContain("계산했어요")
                .contains(copy.get("chat.historyResultOmitted"));
    }

    /** 계산 결과 바로 다음 질문의 기록에는 그 결과가 들어 있다. 금액 대신 안내 문장이어야 한다. */
    private JsonNode askHistoryIncludingRecalc() throws Exception {
        recalcTurn("월 200만원으로", Map.of("monthlySavingAmount", 1_500_000), Map.of("monthlySavingAmount", 2_000_000));
        return askWithAnswer("결과 어때요?", AiServerStub.ANSWER_OK).get("history");
    }

    @Test
    @DisplayName("변경 목록은 그 질문을 접수한 계획 버전의 것만 쓴다. 입력 화면에서 계획을 바꾸면 이전 목록은 쓰지 않는다")
    void listBelongsToPlanVersion() throws Exception {
        recalcTurn("적금 30으로", Map.of(), Map.of("savingsRatioPct", 30));
        long revision = planSaveService.findCurrent(memberId).orElseThrow().revision();
        planSaveService.save(memberId, valid().goalAmount(20_000_000L).targetPeriodMonths(36).heldAssets(5_000_000L)
                .monthlySavingAmount(400_000L).savingsRatioPct(50).build(), revision);

        JsonNode next = askWithAnswer("다음은?", AiServerStub.ANSWER_OK);
        assertThat(next.path("accumulatedChanges")).isEmpty();
    }
}
