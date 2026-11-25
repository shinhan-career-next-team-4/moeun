package com.moeun.backend.chat.application;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.moeun.backend.calc.ChatComparisonQuery;
import com.moeun.backend.chat.domain.ChatMessageRepository;
import com.moeun.backend.chat.domain.ConditionChangeRepository;
import com.moeun.backend.chat.domain.RecalcResultRow;
import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.NoPlanException;
import com.moeun.backend.plan.PlanApplyService;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.plan.StaleBaseVersionException;

import tools.jackson.databind.json.JsonMapper;

/**
 * F-5 대화 비교 적용. chat이 맡는 검사 2~4(메시지·최신 여부·기준 버전)를 하고, 저장된 바꾼 조건을 키 변환 없이 plan의
 * {@link PlanApplyService}에 넘긴다. 조건 재검증·revision·기준 버전 일치 검사(5~10)와 저장은 plan 한 트랜잭션이 한다.
 * 이 서비스는 트랜잭션을 열지 않는다(읽기 3번 + plan 호출 1번이고, 경합은 plan의 @Version 낙관적 락이 막는다).
 * 검사 3과 plan 호출 사이에 새 비교가 저장되면 그 틈에는 이미 최신이 아닌 비교가 적용될 수 있다. 기준 버전·revision 검사는
 * plan이 지키므로 계획 데이터는 어긋나지 않는다.
 */
@Service
public class ChatApplyService {

    private final ChatMessageRepository messages;
    private final ChatComparisonQuery comparisons;
    private final PlanApplyService planApply;
    private final PlanSaveService plans;
    private final JsonMapper json;
    private final ConditionChangeRepository conditionChanges;

    ChatApplyService(ChatMessageRepository messages, ChatComparisonQuery comparisons, PlanApplyService planApply,
            PlanSaveService plans, JsonMapper json, ConditionChangeRepository conditionChanges) {
        this.conditionChanges = conditionChanges;
        this.messages = messages;
        this.comparisons = comparisons;
        this.planApply = planApply;
        this.plans = plans;
        this.json = json;
    }

    /**
     * @return 적용 뒤 새 plan.revision
     * @throws ComparisonNotFoundException 없는·남의·RECALC_RESULT가 아니거나 COMPLETED가 아닌 메시지(404)
     * @throws StaleBaseVersionException 그 회원의 가장 최근 COMPLETED RECALC_RESULT가 아님(409 STALE_COMPARISON).
     *     plan의 검사 8보다 항상 먼저 판단한다
     * @throws NoPlanException 저장된 계획이 없음(409 NO_PLAN)
     */
    public long apply(long memberId, long messageId, long expectedRevision) {
        RecalcResultRow row = messages.findCompletedRecalcResult(messageId, memberId)
                .orElseThrow(ComparisonNotFoundException::new);
        boolean isLatest = messages.findLatestCompletedRecalcResultId(memberId)
                .filter(latestId -> latestId == messageId).isPresent();
        if (!isLatest) {
            throw new StaleBaseVersionException();   // 최신이 아닌 비교도 plan 검사 9와 같은 409 STALE_COMPARISON이다
        }
        long baseVersionId = baseVersionId(memberId, messageId, row);
        // 쌓인 조건(비교 기준)과 이번 변경을 합친 최종 조건이 새 계획이 된다. 그 뒤 변경 목록을 비운다(#418, D-103)
        long revision = planApply.apply(memberId, ChangePath.CHAT, StoredConditionChanges.merged(json, row),
                expectedRevision, baseVersionId).revision();
        conditionChanges.deleteAllOf(memberId);
        return revision;
    }

    /**
     * 검사 4. 계약은 실패하지 않는다고 보지만 calc는 plan 행이 없으면 기록을 소유한 것으로 보지 않아 빈 값을 준다. 계획이 없는
     * 회원은 plan의 검사 5와 같은 NO_PLAN으로 답하고, 계획이 있는데 비면 저장된 데이터가 어긋난 것이다(500).
     */
    private long baseVersionId(long memberId, long messageId, RecalcResultRow row) {
        Optional<Long> baseVersionId = comparisons.findChatRunPlanVersion(memberId, row.calcRunId());
        if (baseVersionId.isPresent()) {
            return baseVersionId.get();
        }
        if (plans.findCurrent(memberId).isEmpty()) {
            throw new NoPlanException();
        }
        throw new IllegalStateException(
                "RECALC_RESULT " + messageId + " does not point to the member's calc_run(CHAT)");
    }
}
