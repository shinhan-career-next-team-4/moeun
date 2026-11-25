package com.moeun.backend.chat.domain;

/**
 * 답변을 기다리거나 받은 ASSISTANT 메시지와 그 상태 전이 규칙(#312). PENDING에서만 {@link #complete}·{@link #fail}로 바뀐다.
 * 전이는 새 값을 담은 {@link MessageTransition}을 돌려주고, 저장소가 아직 PENDING일 때만 반영한다.
 *
 * @param calcRunId EXPLANATION이면 설명할 계산 기록. 질문 답변이면 null이다
 * @param baselineScenario EXPLANATION이면 요청 시점의 기준 시나리오. 질문 답변이면 null이다
 */
public record ChatMessage(long id, long memberId, MessageType type, MessageStatus status, Long calcRunId,
        String baselineScenario) {

    public boolean isPending() {
        return status == MessageStatus.PENDING;
    }

    public boolean isExplanation() {
        return type == MessageType.EXPLANATION;
    }

    /**
     * ai-server의 답으로 완료한다. 답이 비워 둔 계산 기록·기준 시나리오는 지금 값을 잇는다. 단 OUT_OF_SCOPE는 계산 기록을
     * 가리키지 않으므로(ck_cm_out_of_scope) 계산 기록을 비운다.
     *
     * @throws IllegalStateException PENDING이 아닐 때
     * @throws IllegalArgumentException 답의 종류(ANSWER·EXPLANATION·RECALC_RESULT·CLARIFY·OUT_OF_SCOPE)가 아닐 때
     */
    public MessageTransition complete(MessageType answerType, String content, Long answerCalcRunId,
            String answerBaselineScenario, String conditionChangesJson) {
        return complete(answerType, content, answerCalcRunId, answerBaselineScenario, conditionChangesJson, null);
    }

    /**
     * 대화 조건 누적(#418, D-103)의 비교 기준과 함께 완료한다. 비교 기준은 다시 계산한 비교(RECALC_RESULT)에만 남는다.
     *
     * @param baselineConditionChangesJson 그 계산의 비교 기준(그때까지 쌓인 조건의 최종 값). 없으면 null
     */
    public MessageTransition complete(MessageType answerType, String content, Long answerCalcRunId,
            String answerBaselineScenario, String conditionChangesJson, String baselineConditionChangesJson) {
        requirePending();
        if (!answerType.isAnswer()) {
            throw new IllegalArgumentException("답의 종류가 아니다: " + answerType);
        }
        Long newCalcRunId = answerType == MessageType.OUT_OF_SCOPE ? null
                : answerCalcRunId != null ? answerCalcRunId : calcRunId;
        String newBaseline = answerBaselineScenario != null ? answerBaselineScenario : baselineScenario;
        String newBaselineChanges = answerType == MessageType.RECALC_RESULT ? baselineConditionChangesJson : null;
        return new MessageTransition(id, memberId, status, MessageStatus.COMPLETED, answerType, content, newCalcRunId,
                newBaseline, conditionChangesJson, newBaselineChanges);
    }

    /**
     * 실패로 끝낸다. 계산 기록·기준 시나리오는 남긴다(실패한 설명도 '설명 다시 요청'의 원본이다, FR-AI-05).
     *
     * @throws IllegalStateException PENDING이 아닐 때
     */
    public MessageTransition fail(String failedContent) {
        requirePending();
        return new MessageTransition(id, memberId, status, MessageStatus.FAILED, MessageType.FAILURE, failedContent,
                calcRunId, baselineScenario, null, null);
    }

    private void requirePending() {
        if (!isPending()) {
            throw new IllegalStateException("PENDING이 아닌 메시지는 바꿀 수 없다: " + id + " " + status);
        }
    }
}
