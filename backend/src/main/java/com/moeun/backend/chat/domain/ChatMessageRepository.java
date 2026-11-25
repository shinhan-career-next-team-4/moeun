package com.moeun.backend.chat.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * chat_message·chat_message_citation 저장소. 모든 메서드가 memberId 조건을 받는다(소유권은 DB가 막지 않는다).
 * 구현은 chat.infrastructure에 있다. 상태 전이는 행이 아직 전이 전 상태일 때만 반영하고 바뀐 행 수를 돌려준다.
 * 0행이면 그 사이 삭제·정리·완료된 것이라 부르는 쪽이 무시한다.
 */
public interface ChatMessageRepository {

    long insertQuestion(long memberId, String text);

    long insertPendingAnswer(long memberId);

    /**
     * F-3: 설명 대기 행. 기준 시나리오는 요청 시점 값을 저장해 두고 리스너가 그 값으로 EXPLAIN을 보낸다.
     * retryOfMessageId는 '설명 다시 요청'의 원본(FR-AI-05)이고 다시 요청이 아니면 null이다.
     */
    long insertPendingExplanation(long memberId, long calcRunId, String baselineScenario, Long retryOfMessageId);

    /**
     * 그 회원의 설명 행인지(FR-AI-05의 다시 요청 원본). 완료·대기 설명은 EXPLANATION이고, 실패한 설명은 계산 기록을
     * 가진 FAILURE다(질문 답변은 실패해도 계산 기록이 없다). 없는·남의·설명이 아닌 행은 모두 false다.
     */
    boolean existsExplanationOf(long id, long memberId);

    /** 그 회원이 같은 기록으로 이미 요청한 설명이 PENDING인지. 동시 요청으로 여러 행일 수 있어 존재 여부만 본다. */
    boolean existsPendingExplanation(long memberId, long calcRunId);

    boolean existsPendingAnswer(long memberId);

    /** 그 회원의 PENDING(질문·설명 모두)이 하나라도 있는지. 목록 밖의 오래된 id도 잡힌다. */
    boolean existsPending(long memberId);

    /** 복원: 최근 limit개를 id 오름차순으로. */
    List<MessageRow> findLatest(long memberId, int limit);

    /** 폴링: afterId보다 큰 id의 자기 메시지 전부를 id 오름차순으로. */
    List<MessageRow> findAfter(long memberId, long afterId);

    /**
     * 그 회원의 PENDING 중 createdBefore보다 먼저 만들어진 행을 모두 실패로 끝낸다. 행마다 {@link ChatMessage#fail}과 같은
     * 전이다. 경계(같은 시각)는 건드리지 않는다.
     */
    int failPendingCreatedBefore(long memberId, OffsetDateTime createdBefore, String failedContent);

    Optional<ChatMessage> findPending(long id, long memberId);

    /**
     * 행이 아직 {@code transition.from()} 상태일 때만 반영한다. 값은 미리 읽어 둔 행에서 계산했다({@link MessageTransition}).
     *
     * @return 바뀐 행 수(0 또는 1)
     */
    int apply(MessageTransition transition);

    /** F-4: 그 회원의 COMPLETED RECALC_RESULT 한 행. 없는·남의·다른 종류·COMPLETED가 아닌 행은 모두 빈 값이다. */
    Optional<RecalcResultRow> findCompletedRecalcResult(long id, long memberId);

    /** F-4 applicable 판정 ①: 그 회원의 COMPLETED RECALC_RESULT 중 id가 가장 큰 행. */
    Optional<Long> findLatestCompletedRecalcResultId(long memberId);

    /** beforeId보다 앞서 저장된 가장 최근 질문(답변 행 바로 앞에 저장된 USER 행). */
    Optional<QuestionMessage> findLatestQuestionBefore(long memberId, long beforeId);

    /** beforeId보다 앞선 COMPLETED 메시지 중 최근 limit개를 오래된 것부터. */
    List<HistoryRow> findCompletedBefore(long memberId, long beforeId, int limit);

    /** 인용 chunk를 순서(rank 1부터)대로 저장한다. 그 회원의 메시지일 때만 저장한다. */
    void insertCitations(long memberId, long messageId, List<Long> chunkIds);

    /**
     * 그 회원의 대화를 한 문장으로 전부 지운다(#46). 행을 하나씩 지우면 retry_of_message_id(NO ACTION)가 문장마다
     * 검사되어 순서에 따라 실패하므로 일괄 DELETE다. chat_message_citation은 FK CASCADE로 함께 지워진다.
     */
    int deleteAllOf(long memberId);
}
