package com.moeun.backend.chat;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.moeun.backend.calc.application.CompareConditionsUseCase;
import com.moeun.backend.calc.application.ConditionChanges;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.plan.PlanView;

import tools.jackson.databind.JsonNode;

/**
 * 대화 조건 누적(#418)·대화로 되돌리기(#419) 테스트 공통 기반. ai-server는 스텁이다. 스텁은 /internal/calc를 부르지 못하므로
 * 같은 계산을 테스트가 먼저 해서 그 calcRunId로 답하게 한다.
 */
@TestPropertySource(properties = "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv")
abstract class ChatAccumulationTestSupport extends ChatIntegrationTestSupport {

    @Autowired
    PlanSaveService planSaveService;

    @Autowired
    CompareConditionsUseCase compareConditions;

    @BeforeEach
    void saveDemoPlan() {
        Long revision = planSaveService.findCurrent(memberId).map(PlanView::revision).orElse(null);
        // 목표 2,000만원, 36개월, 보유 500만원, 월 30만원, 기본금리, 비중 5:5
        planSaveService.save(memberId, valid().goalAmount(20_000_000L).targetPeriodMonths(36).heldAssets(5_000_000L)
                .monthlySavingAmount(300_000L).savingsRatioPct(50).build(), revision);
    }

    long planVersion() {
        return planSaveService.findCurrent(memberId).orElseThrow().currentVersionId();
    }

    /**
     * ai-server가 하는 일을 흉내 낸 한 턴: 지금까지 쌓인 조건(baseline)을 비교 기준으로 이번 변경을 계산하고, 그 calcRunId와
     * 이번 변경(snake_case)으로 RECALC_RESULT를 답한다. 돌려주는 값은 ai-server가 받은 요청 본문이다.
     */
    JsonNode recalcTurn(String text, Map<String, Object> baseline, Map<String, Object> turn) throws Exception {
        CompareConditionsUseCase.ComparisonResult result = compareConditions.compare(memberId,
                baseline.isEmpty() ? null : conversion(baseline), conversion(turn).override(),
                conversion(turn).etfProductCode(), planVersion());
        Map<String, Object> snake = new LinkedHashMap<>();
        turn.forEach((key, value) -> snake.put(snake(key), value));
        AI.respond(200, JSON.writeValueAsString(Map.of("messageType", "RECALC_RESULT", "content", "계산했어요",
                "baselineScenario", "AVG", "calcRunId", result.calcRunId(), "conditionChanges", snake,
                "citationChunkIds", List.of())));
        int before = AI.requests().size();
        long id = askAndGetAssistantId(cookie, text);
        awaitStatus(id, "COMPLETED");
        return awaitAiRequests(before + 1).get(before);
    }

    JsonNode askWithAnswer(String text, String answerJson) throws Exception {
        AI.respond(200, answerJson);
        int before = AI.requests().size();
        long id = askAndGetAssistantId(cookie, text);
        awaitStatus(id, "COMPLETED");
        return awaitAiRequests(before + 1).get(before);
    }

    static ConditionChanges.Conversion conversion(Map<String, Object> changes) {
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        changes.forEach((key, value) -> nodes.put(key, JSON.valueToTree(value)));
        return ConditionChanges.convert(nodes);
    }

    static String snake(String camel) {
        return camel.replaceAll("([A-Z])", "_$1").toLowerCase();
    }

    static List<String> fields(JsonNode request) {
        List<String> result = new ArrayList<>();
        request.path("accumulatedChanges").forEach(entry -> result.add(entry.get("field").asString()));
        return result;
    }

    long latestRecalcId() {
        return jdbc.sql("SELECT max(id) FROM chat_message WHERE member_id = ? AND message_type = 'RECALC_RESULT'")
                .param(memberId).query(Long.class).single();
    }

    static final String CLARIFY = """
            {"messageType":"CLARIFY","content":"허용 범위를 벗어났어요. 다시 알려 주세요","baselineScenario":"AVG",
             "calcRunId":null,"conditionChanges":null,"citationChunkIds":[]}""";
}
