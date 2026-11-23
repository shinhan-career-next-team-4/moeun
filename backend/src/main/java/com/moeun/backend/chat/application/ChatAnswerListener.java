package com.moeun.backend.chat.application;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.moeun.backend.calc.Scenario;
import com.moeun.backend.chat.domain.ChatMessageRepository;
import com.moeun.backend.chat.domain.HistoryMessage;
import com.moeun.backend.chat.domain.PendingMessage;
import com.moeun.backend.chat.domain.QuestionMessage;
import com.moeun.backend.config.copy.CopyBook;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 답변 대기 행에 대해 ai-server POST /chat을 불러 COMPLETED 또는 FAILED로 바꾼다.
 * 트랜잭션 없이 돈다: DB 읽기와 저장은 각각 짧은 트랜잭션(TransactionTemplate)으로 끝내고, 느린 HTTP 호출 동안
 * DB 커넥션을 붙들지 않는다(리스너 범위에서 템플릿 밖으로 JdbcClient를 직접 부르면 범위가 끝날 때까지 커넥션이 묶인다).
 * 어떤 실패든 행을 FAILED로 바꾸고 정상 종료한다(이벤트가 완료로 기록되고 PENDING에 갇히지 않는다).
 */
@Component
public class ChatAnswerListener {

    private static final Logger LOG = LoggerFactory.getLogger(ChatAnswerListener.class);
    private static final int HISTORY_LIMIT = 20;
    private static final String EXPLANATION = "EXPLANATION";
    private static final Map<String, String> CONDITION_KEYS = Map.of(
            "goal_amount", "goalAmount",
            "monthly_saving_amount", "monthlySavingAmount",
            "savings_ratio_pct", "savingsRatioPct",
            "savings_rate_input_pct", "savingsRateInputPct",
            "target_period_months", "targetPeriodMonths");

    private final ChatMessageRepository messages;
    private final PlanContextPort planContexts;
    private final AiChatClient aiClient;
    private final CopyBook copy;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    ChatAnswerListener(ChatMessageRepository messages, PlanContextPort planContexts, AiChatClient aiClient,
            CopyBook copy, TransactionTemplate transactions, JsonMapper json) {
        this.messages = messages;
        this.planContexts = planContexts;
        this.aiClient = aiClient;
        this.copy = copy;
        this.transactions = transactions;
        this.json = json;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(ChatAnswerRequested event) {
        Optional<Snapshot> read = transactions.execute(status -> read(event));
        if (read == null || read.isEmpty()) {
            // 이미 끝났거나(재처리) 삭제·정리된 행이다
            LOG.info("chat.answer.skipped messageId={} memberId={}", event.assistantMessageId(), event.memberId());
            return;
        }
        PendingMessage message = read.get().message();
        try {
            AiChatRequest request = buildRequest(read.get());
            AiChatAnswer answer;
            try {
                answer = aiClient.chat(request);
            } catch (RuntimeException e) {
                // ai-server 예외 메시지에는 응답 본문이 들어 있을 수 있어 예외를 붙이지 않고 분류·상태 코드만 남긴다.
                // 값은 구조화된 필드(key-value)로 운반한다(#123 로그 계약). 문장에는 값을 넣지 않는다
                var warn = LOG.atWarn()
                        .addKeyValue("messageId", message.id())
                        .addKeyValue("memberId", message.memberId())
                        .addKeyValue("error", errorKind(e));
                Integer status = statusCode(e);
                if (status != null) {
                    warn = warn.addKeyValue("status", status);
                }
                warn.log("chat.answer.aiFailed");
                fail(message);
                return;
            }
            complete(message, answer);
        } catch (RuntimeException e) {
            LOG.warn("chat.answer.failed messageId={} memberId={}", message.id(), message.memberId(), e);
            fail(message);
        }
    }

    private static String errorKind(RuntimeException error) {
        if (error instanceof RestClientResponseException) {
            return "HTTP_ERROR";
        }
        if (error instanceof ResourceAccessException) {
            for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
                    return "CONNECT";
                }
                if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                    return "TIMEOUT";
                }
            }
            return "IO";
        }
        if (error instanceof AiServerException) {
            return "BAD_RESPONSE";
        }
        return error.getClass().getSimpleName();
    }

    private static Integer statusCode(RuntimeException error) {
        return error instanceof RestClientResponseException response ? response.getStatusCode().value() : null;
    }

    /**
     * ai-server를 부르기 전에 필요한 DB 읽기를 짧은 트랜잭션 하나로 끝낸다. 이 트랜잭션이 끝나면 커넥션이 풀로 돌아가므로
     * 이후의 느린 HTTP 호출 동안 커넥션을 붙들지 않는다. PENDING이 아니면 비워서 돌려준다(planContext도 만들지 않는다).
     */
    private Optional<Snapshot> read(ChatAnswerRequested event) {
        Optional<PendingMessage> found = messages.findPending(event.assistantMessageId(), event.memberId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PendingMessage message = found.get();
        if (EXPLANATION.equals(message.messageType())) {
            return Optional.of(new Snapshot(message, null,
                    messages.findCompletedBefore(message.memberId(), message.id(), HISTORY_LIMIT)));
        }
        QuestionMessage question = messages.findLatestQuestionBefore(message.memberId(), message.id()).orElse(null);
        List<HistoryMessage> history = question == null ? List.of()
                : messages.findCompletedBefore(message.memberId(), question.id(), HISTORY_LIMIT);
        return Optional.of(new Snapshot(message, question, history));
    }

    private AiChatRequest buildRequest(Snapshot snapshot) {
        if (EXPLANATION.equals(snapshot.message().messageType())) {
            return buildExplainRequest(snapshot);
        }
        return buildQuestionRequest(snapshot);
    }

    private AiChatRequest buildQuestionRequest(Snapshot snapshot) {
        PendingMessage message = snapshot.message();
        if (snapshot.question() == null) {
            throw new IllegalStateException("질문 행을 찾지 못함");
        }
        Optional<PlanContextSnapshot> plan = planContexts.findForCurrentPlan(message.memberId());
        Scenario baseline = plan.map(PlanContextSnapshot::baselineScenario).orElse(Scenario.AVG);
        return new AiChatRequest(message.memberId(), message.id(), "QUESTION", snapshot.question().content(),
                baseline.name(), null, plan.map(PlanContextSnapshot::body).orElse(null), snapshot.history());
    }

    private AiChatRequest buildExplainRequest(Snapshot snapshot) {
        PendingMessage message = snapshot.message();
        long calcRunId = message.calcRunId();
        Scenario baseline = Scenario.valueOf(message.baselineScenario());
        PlanContextSnapshot plan = planContexts.findForCalcRun(message.memberId(), calcRunId, baseline)
                .orElseThrow(() -> new IllegalStateException("설명할 계산 기록을 찾지 못함"));
        return new AiChatRequest(message.memberId(), message.id(), "EXPLAIN", null, baseline.name(), calcRunId,
                plan.body(), snapshot.history());
    }

    /** 갱신과 citation 저장은 한 트랜잭션이다. 0행이면(그 사이 삭제·정리됨) 답을 버리고 citation도 넣지 않는다. */
    private void complete(PendingMessage message, AiChatAnswer answer) {
        Boolean saved = transactions.execute(status -> {
            int updated = messages.complete(message.id(), message.memberId(), answer.messageType(), answer.content(),
                    answer.calcRunId(), answer.baselineScenario(), conditionChangesJson(answer.conditionChanges()));
            if (updated == 0) {
                return false;
            }
            messages.insertCitations(message.memberId(), message.id(), answer.citationChunkIds());
            return true;
        });
        if (Boolean.TRUE.equals(saved)) {
            LOG.info("chat.answer.completed messageId={} type={}", message.id(), answer.messageType());
        } else {
            LOG.info("chat.answer.discarded messageId={} memberId={}", message.id(), message.memberId());
        }
    }

    /** ai-server의 snake_case 조건 키를 camelCase로 바꾼다. 허용 5키 밖의 키는 그대로 둔다. */
    private String conditionChangesJson(JsonNode changes) {
        if (changes == null) {
            return null;
        }
        ObjectNode converted = json.createObjectNode();
        changes.properties().forEach(entry ->
                converted.set(CONDITION_KEYS.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue()));
        return json.writeValueAsString(converted);
    }

    private void fail(PendingMessage message) {
        try {
            transactions.executeWithoutResult(status ->
                    messages.fail(message.id(), message.memberId(), copy.get("chat.failed")));
        } catch (RuntimeException e) {
            // 이벤트를 미완료로 남겨 재기동 때 다시 처리하게 한다
            LOG.error("chat.answer.failUpdateFailed messageId={}", message.id(), e);
            throw e;
        }
    }

    /** 호출 전에 읽어 둔 값. 설명(EXPLAIN)이면 question이 없다. */
    private record Snapshot(PendingMessage message, QuestionMessage question, List<HistoryMessage> history) {
    }
}
