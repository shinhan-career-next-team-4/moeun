package com.moeun.backend.chat.application;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.calc.ChatComparisonQuery;
import com.moeun.backend.calc.ChatComparisonView;
import com.moeun.backend.chat.domain.ChatMessageRepository;
import com.moeun.backend.chat.domain.RecalcResultRow;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.plan.PlanView;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-4 비교 결과 조회. 저장된 RECALC_RESULT의 바꾼 조건을 키 변환 없이 calc의 재계산에 넘기고(결과는 저장하지 않는다),
 * applicable을 판정한다. 범위 검사는 하지 않는다(그때 계산한 값의 재현이 목적). 변환할 수 없는 저장값은 예외로 올라와
 * 공통 처리기가 500 INTERNAL_ERROR로 바꾼다.
 */
@Service
public class ChatComparisonService {

    private static final String ETF_PRODUCT_CODE = "etfProductCode";

    private final ChatMessageRepository messages;
    private final ChatComparisonQuery comparisons;
    private final PlanSaveService plans;
    private final JsonMapper json;

    ChatComparisonService(ChatMessageRepository messages, ChatComparisonQuery comparisons, PlanSaveService plans,
            JsonMapper json) {
        this.messages = messages;
        this.comparisons = comparisons;
        this.plans = plans;
        this.json = json;
    }

    /** @throws ComparisonNotFoundException 없는·남의·RECALC_RESULT가 아니거나 COMPLETED가 아닌 메시지 */
    @Transactional(readOnly = true)
    public ChatComparison find(long memberId, long messageId) {
        RecalcResultRow row = messages.findCompletedRecalcResult(messageId, memberId)
                .orElseThrow(ComparisonNotFoundException::new);
        Map<String, JsonNode> changes = StoredConditionChanges.parse(json, row);
        ChatComparisonView view = comparisons.recompute(memberId, row.calcRunId(), changes)
                .orElseThrow(() -> new IllegalStateException(
                        "RECALC_RESULT " + messageId + " does not point to the member's calc_run(CHAT)"));
        boolean applicable = !changesEtfProduct(changes) && isApplicable(memberId, messageId, view);
        return new ChatComparison(messageId, applicable, List.copyOf(changes.keySet()), view);
    }

    /**
     * 대화에서 ETF 종목을 바꾼 비교인지(D-097). 계획에 종목을 저장하는 #154 전까지는 적용할 수 없다. F-5도 같은 비교를 대화
     * 경로 규칙(rules.yml CHAT에 ETF_PRODUCT_CODE 없음)으로 422 FIELD_NOT_CHANGEABLE 거절하므로, 키가 있으면 계획과 같은
     * 종목이어도 적용 불가로 맞춘다. #154 뒤 CHAT에 ETF_PRODUCT_CODE를 더할 때 이 검사를 지운다(#268).
     */
    private static boolean changesEtfProduct(Map<String, JsonNode> changes) {
        JsonNode etfProduct = changes.get(ETF_PRODUCT_CODE);
        return etfProduct != null && !etfProduct.isNull();
    }

    /** ① 그 회원의 COMPLETED RECALC_RESULT 중 id가 가장 크고 ② 그 기록의 계획 버전이 지금의 현재 계획 버전일 때만 true. */
    private boolean isApplicable(long memberId, long messageId, ChatComparisonView view) {
        boolean isLatest = messages.findLatestCompletedRecalcResultId(memberId)
                .filter(latestId -> latestId == messageId).isPresent();
        return isLatest && plans.findCurrent(memberId).map(PlanView::currentVersionId)
                .filter(currentVersionId -> currentVersionId == view.basePlanVersionId()).isPresent();
    }
}
