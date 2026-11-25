package com.moeun.backend.chat.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.chat.domain.ChatMessageRepository;
import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.plan.PlanView;

/** F-1 질문 보내기. 정리·판정·두 행 저장·이벤트 발행을 한 트랜잭션에서 한다. */
@Service
public class ChatQuestionService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatQuestionService.class);
    private static final int MIN_LENGTH = 1;
    private static final int MAX_LENGTH = 2000;

    private final ChatMessageRepository messages;
    private final StalePendingCleaner cleaner;
    private final ApplicationEventPublisher events;
    private final PlanSaveService plans;

    ChatQuestionService(ChatMessageRepository messages, StalePendingCleaner cleaner,
            ApplicationEventPublisher events, PlanSaveService plans) {
        this.messages = messages;
        this.cleaner = cleaner;
        this.events = events;
        this.plans = plans;
    }

    /** 409로 끝나도 방금 한 오래된 PENDING 정리는 남긴다. */
    @Transactional(noRollbackFor = AnswerPendingException.class)
    public AcceptedQuestion ask(long memberId, String rawText) {
        String text = validate(rawText);
        cleaner.failStale(memberId);
        if (messages.existsPendingAnswer(memberId)) {
            throw new AnswerPendingException();
        }
        long userMessageId = messages.insertQuestion(memberId, text);
        long assistantMessageId = messages.insertPendingAnswer(memberId);
        // 질문을 접수한 순간의 계획 버전을 기록한다. 답을 기다리는 사이 계획이 바뀌어도 이 버전 기준으로 비교한다(#396)
        Long basePlanVersionId = plans.findCurrent(memberId).map(PlanView::currentVersionId).orElse(null);
        events.publishEvent(new ChatAnswerRequested(assistantMessageId, memberId, basePlanVersionId));
        LOG.info("chat.question.accepted memberId={} assistantMessageId={}", memberId, assistantMessageId);
        return new AcceptedQuestion(userMessageId, assistantMessageId);
    }

    /** 유니코드 공백(전각 포함)을 떼고(String.strip) UTF-16 단위로 길이를 잰다. */
    private static String validate(String rawText) {
        String text = rawText == null ? "" : rawText.strip();
        if (text.isEmpty()) {
            throw new ChatValidationException(FieldError.required("text"));
        }
        if (text.length() > MAX_LENGTH) {
            throw new ChatValidationException(
                    new FieldError("text", "OUT_OF_RANGE", (long) MIN_LENGTH, (long) MAX_LENGTH, "CHAR", null));
        }
        return text;
    }
}
