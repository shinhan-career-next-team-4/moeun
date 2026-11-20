package com.moeun.backend.chat.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.calc.PlanContextProvider;
import com.moeun.backend.calc.PlanRunRef;
import com.moeun.backend.chat.domain.ChatMessageRepository;

/**
 * F-3 설명 요청. 정리 → 소유권·기준 시나리오 확인 → 설명 대기 행 저장 → 이벤트 발행을 한 트랜잭션에서 한다.
 * 재계산·ai-server 호출은 하지 않는다(리스너가 커밋 뒤 트랜잭션 밖에서 한다).
 */
@Service
public class ChatExplanationService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatExplanationService.class);

    private final ChatMessageRepository messages;
    private final StalePendingCleaner cleaner;
    private final PlanContextProvider planContexts;
    private final ApplicationEventPublisher events;

    ChatExplanationService(ChatMessageRepository messages, StalePendingCleaner cleaner,
            PlanContextProvider planContexts, ApplicationEventPublisher events) {
        this.messages = messages;
        this.cleaner = cleaner;
        this.planContexts = planContexts;
        this.events = events;
    }

    /** 404로 끝나도 방금 한 오래된 PENDING 정리는 남긴다. 그 밖의 예외는 정리·저장·발행을 함께 되돌린다. */
    @Transactional(noRollbackFor = ExplanationRunNotFoundException.class)
    public void request(long memberId, long calcRunId) {
        cleaner.failStale(memberId);
        PlanRunRef run = planContexts.findPlanRun(memberId, calcRunId)
                .orElseThrow(ExplanationRunNotFoundException::new);
        if (messages.existsPendingExplanation(memberId, calcRunId)) {
            LOG.info("chat.explanation.reused memberId={} calcRunId={}", memberId, calcRunId);
            return;
        }
        long assistantMessageId = messages.insertPendingExplanation(memberId, calcRunId, run.baselineScenario().name());
        events.publishEvent(new ChatAnswerRequested(assistantMessageId, memberId));
        LOG.info("chat.explanation.accepted memberId={} calcRunId={} assistantMessageId={}", memberId, calcRunId,
                assistantMessageId);
    }
}
