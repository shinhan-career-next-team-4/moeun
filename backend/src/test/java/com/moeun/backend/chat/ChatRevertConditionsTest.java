package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.calc.application.CompareConditionsUseCase;

import tools.jackson.databind.JsonNode;

/**
 * 대화로 되돌리기(#419, D-103). ai-server가 되돌리기를 실행하면 답의 revert{scope, condition}대로 Spring이 변경 목록에서
 * 항목을 지운다. 되돌리기 답은 목록에 덧붙이지 않는다. last는 가장 최근 질문(메시지)의 변경 전부, condition은 그 조건의
 * 변경 전부, all은 목록 전부다. 남은 조건의 비교는 내 계획 대비라 비교 기준을 남기지 않는다.
 */
class ChatRevertConditionsTest extends ChatAccumulationTestSupport {

    /** 비중 30:70 → 월 150만원의 두 변경을 쌓는다. */
    private void twoChanges() throws Exception {
        recalcTurn("적금 30으로", Map.of(), Map.of("savingsRatioPct", 30));
        recalcTurn("월 150만원으로", Map.of("savingsRatioPct", 30), Map.of("monthlySavingAmount", 1_500_000));
    }

    /**
     * ai-server가 되돌리기를 실행한 답을 흉내 낸다. 남은 조건이 있으면 그것을 내 계획 대비로 계산한 RECALC_RESULT,
     * 없으면 계산 없는 ANSWER다. 돌려주는 값은 ai-server가 받은 요청 본문이다.
     */
    private JsonNode revertTurn(String text, Map<String, Object> revert, Map<String, Object> remaining)
            throws Exception {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("content", "되돌렸어요");
        answer.put("baselineScenario", "AVG");
        answer.put("citationChunkIds", List.of());
        answer.put("revert", revert);
        if (remaining.isEmpty()) {
            answer.put("messageType", "ANSWER");
        } else {
            CompareConditionsUseCase.ComparisonResult result = compareConditions.compare(memberId, null,
                    conversion(remaining).override(), conversion(remaining).etfProductCode(), planVersion());
            Map<String, Object> snake = new LinkedHashMap<>();
            remaining.forEach((key, value) -> snake.put(snake(key), value));
            answer.put("messageType", "RECALC_RESULT");
            answer.put("calcRunId", result.calcRunId());
            answer.put("conditionChanges", snake);
        }
        return askWithAnswer(text, JSON.writeValueAsString(answer));
    }

    private JsonNode nextRequest() throws Exception {
        return askWithAnswer("다음은?", AiServerStub.ANSWER_OK);
    }

    @Test
    @DisplayName("변경 항목마다 그 변경을 계산한 메시지 id를 ai-server에 함께 보낸다(직전 변경 취소의 단위)")
    void sendsMessageIdOfEachChange() throws Exception {
        twoChanges();

        JsonNode list = nextRequest().get("accumulatedChanges");

        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("messageId").asLong()).isLessThan(list.get(1).get("messageId").asLong());
    }

    @Test
    @DisplayName("변경 2개에서 \"원래대로\"(last) → 월 저축액만 지우고 비중은 남는다. 되돌리기 답은 목록에 덧붙이지 않는다")
    void lastRemovesOnlyLatestChange() throws Exception {
        twoChanges();

        revertTurn("원래대로", Map.of("scope", "last"), Map.of("savingsRatioPct", 30));

        assertThat(fields(nextRequest())).containsExactly("savingsRatioPct");
    }

    @Test
    @DisplayName("되돌린 결과 카드(F-4)는 내 계획 대비 남은 조건이고 쌓인 조건도 남은 조건뿐이다")
    void revertComparisonIsAgainstMyPlan() throws Exception {
        twoChanges();
        revertTurn("원래대로", Map.of("scope", "last"), Map.of("savingsRatioPct", 30));

        JsonNode body = JSON.readTree(mockMvc.perform(get("/api/chat/messages/{id}/comparison", latestRecalcId())
                .cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(body.get("conditions").get("current").get("savingsRatioPct").asInt()).isEqualTo(50);
        assertThat(body.get("conditions").get("changed").get("savingsRatioPct").asInt()).isEqualTo(30);
        assertThat(body.get("conditions").get("changed").get("monthlySavingAmount").asLong()).isEqualTo(300_000L);
        assertThat(body.get("accumulatedKeys")).extracting(JsonNode::asString).containsExactly("savingsRatioPct");
        assertThat(jdbc.sql("SELECT baseline_condition_changes IS NULL FROM chat_message WHERE id = ?")
                .param(latestRecalcId()).query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("\"비중은 원래대로\"(condition) → 비중만 지운다")
    void conditionRemovesOnlyThatCondition() throws Exception {
        twoChanges();

        revertTurn("비중은 원래대로", Map.of("scope", "condition", "condition", "savingsRatioPct"),
                Map.of("monthlySavingAmount", 1_500_000));

        assertThat(fields(nextRequest())).containsExactly("monthlySavingAmount");
    }

    @Test
    @DisplayName("\"다 원래대로\"(all) → 목록이 빈다")
    void allClearsList() throws Exception {
        twoChanges();

        revertTurn("다 원래대로", Map.of("scope", "all"), Map.of());

        assertThat(nextRequest().path("accumulatedChanges")).isEmpty();
    }

    @Test
    @DisplayName("last 직후 \"아니 전부\"(all) → 남은 변경까지 모두 지운다")
    void allAfterLastClearsRest() throws Exception {
        twoChanges();
        revertTurn("원래대로", Map.of("scope", "last"), Map.of("savingsRatioPct", 30));

        JsonNode request = revertTurn("아니 전부", Map.of("scope", "all"), Map.of());

        assertThat(fields(request)).containsExactly("savingsRatioPct");   // 실행할 때 남아 있던 목록
        assertThat(nextRequest().path("accumulatedChanges")).isEmpty();
    }

    @Test
    @DisplayName("같은 조건을 두 번 바꾼 뒤 last → 마지막 변경만 지워 앞 변경(150만)이 남는다")
    void lastOfSameFieldKeepsEarlierChange() throws Exception {
        recalcTurn("월 150만원으로", Map.of(), Map.of("monthlySavingAmount", 1_500_000));
        recalcTurn("월 200만원으로", Map.of("monthlySavingAmount", 1_500_000), Map.of("monthlySavingAmount", 2_000_000));

        revertTurn("취소", Map.of("scope", "last"), Map.of("monthlySavingAmount", 1_500_000));

        JsonNode list = nextRequest().get("accumulatedChanges");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("after").asLong()).isEqualTo(1_500_000L);
    }

    @Test
    @DisplayName("(예외) 되돌리기 범위가 실린 답이라도 되묻기(CLARIFY)면 목록을 지우지 않는다")
    void clarifyWithRevertKeepsList() throws Exception {
        twoChanges();

        askWithAnswer("원래대로", JSON.writeValueAsString(Map.of("messageType", "CLARIFY",
                "content", "어느 조건을 되돌릴까요?", "baselineScenario", "AVG", "citationChunkIds", List.of(),
                "revert", Map.of("scope", "all"))));

        assertThat(fields(nextRequest())).containsExactly("savingsRatioPct", "monthlySavingAmount");
    }

    @Test
    @DisplayName("(예외) 목록이 빈 상태의 되돌리기 답은 아무것도 바꾸지 않는다")
    void revertOnEmptyListIsNoOp() throws Exception {
        revertTurn("취소", Map.of("scope", "last"), Map.of());

        assertThat(nextRequest().path("accumulatedChanges")).isEmpty();
    }
}
