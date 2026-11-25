package com.moeun.backend.chat.application;

/**
 * 질문·설명 대기 행을 만든 뒤 발행한다. 리스너가 ai-server POST /chat을 불러 결과를 저장한다(모듈 내부 이벤트).
 *
 * @param basePlanVersionId 질문을 접수한 순간의 현재 계획 버전(#396). ai-server의 재계산이 이 버전을 기준으로 비교하게 해서,
 *     답을 기다리는 사이 계획이 바뀌어도(ETF 버튼의 즉시 적용 등) 질문한 때의 계획과 비교한다. 설명·계획 없음이면 null
 */
public record ChatAnswerRequested(long assistantMessageId, long memberId, Long basePlanVersionId) {

    public ChatAnswerRequested(long assistantMessageId, long memberId) {
        this(assistantMessageId, memberId, null);
    }
}
