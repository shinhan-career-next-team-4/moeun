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
import com.moeun.backend.chat.domain.ChatMessage;
import com.moeun.backend.chat.domain.ChatMessageRepository;
import com.moeun.backend.chat.domain.ConditionChange;
import com.moeun.backend.chat.domain.ConditionChangeRepository;
import com.moeun.backend.chat.domain.HistoryMessage;
import com.moeun.backend.chat.domain.MessageType;
import com.moeun.backend.chat.domain.QuestionMessage;
import com.moeun.backend.config.copy.CopyBook;
import com.moeun.backend.plan.PlanSaveService;

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
    // 대화 원문은 최근 10개(5번 주고받은 분량)까지만 보낸다(#418). 앞선 조건은 변경 목록이 대신 전한다
    private static final int HISTORY_LIMIT = 10;
    private static final Map<String, String> CONDITION_KEYS = Map.of(
            "goal_amount", "goalAmount",
            "monthly_saving_amount", "monthlySavingAmount",
            "savings_ratio_pct", "savingsRatioPct",
            "savings_rate_input_pct", "savingsRateInputPct",
            "target_period_months", "targetPeriodMonths",
            "etf_product_code", "etfProductCode",
            "goal_custom_name", "goalCustomName");

    private final ChatMessageRepository messages;
    private final PlanContextPort planContexts;
    private final AiChatClient aiClient;
    private final CopyBook copy;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final ConditionChangeRepository conditionChanges;
    private final PlanSaveService plans;

    ChatAnswerListener(ChatMessageRepository messages, PlanContextPort planContexts, AiChatClient aiClient,
            CopyBook copy, TransactionTemplate transactions, JsonMapper json,
            ConditionChangeRepository conditionChanges, PlanSaveService plans) {
        this.conditionChanges = conditionChanges;
        this.plans = plans;
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
        ChatMessage message = read.get().message();
        try {
            AiChatRequest request = buildRequest(read.get());
            AiChatAnswer answer;
            try {
                answer = aiClient.chat(request);
            } catch (RuntimeException e) {
                // 원인 추적용으로 분류·상태 코드와 상세(ai-server 응답 본문 또는 예외 메시지)를 남긴다(#123, #316).
                // 값은 구조화된 필드(key-value)로 운반한다. 문장에는 값을 넣지 않는다. 로그 파일은 보관 기간이 지나면 지운다
                var warn = LOG.atWarn()
                        .addKeyValue("messageId", message.id())
                        .addKeyValue("memberId", message.memberId())
                        .addKeyValue("error", errorKind(e))
                        .addKeyValue("detail", detail(e));
                Integer status = statusCode(e);
                if (status != null) {
                    warn = warn.addKeyValue("status", status);
                }
                warn.log("chat.answer.aiFailed");
                fail(message);
                return;
            }
            complete(read.get(), answer);
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

    private static String detail(RuntimeException error) {
        if (error instanceof RestClientResponseException response) {
            return response.getResponseBodyAsString();
        }
        return String.valueOf(error.getMessage());
    }

    private static Integer statusCode(RuntimeException error) {
        return error instanceof RestClientResponseException response ? response.getStatusCode().value() : null;
    }

    /**
     * ai-server를 부르기 전에 필요한 DB 읽기를 짧은 트랜잭션 하나로 끝낸다. 이 트랜잭션이 끝나면 커넥션이 풀로 돌아가므로
     * 이후의 느린 HTTP 호출 동안 커넥션을 붙들지 않는다. PENDING이 아니면 비워서 돌려준다(planContext도 만들지 않는다).
     */
    private Optional<Snapshot> read(ChatAnswerRequested event) {
        Optional<ChatMessage> found = messages.findPending(event.assistantMessageId(), event.memberId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ChatMessage message = found.get();
        if (message.isExplanation()) {
            return Optional.of(new Snapshot(message, null, history(message.memberId(), message.id()), null,
                    List.of()));
        }
        QuestionMessage question = messages.findLatestQuestionBefore(message.memberId(), message.id()).orElse(null);
        List<HistoryMessage> history = question == null ? List.of() : history(message.memberId(), question.id());
        // 그 질문을 접수한 계획 버전에 쌓인 변경 목록(#418, D-103). 계획이 없으면 쌓인 것도 없다
        Long baseVersion = event.basePlanVersionId();
        List<ConditionChange> accumulated = baseVersion == null ? List.of()
                : conditionChanges.findAll(message.memberId(), baseVersion);
        return Optional.of(new Snapshot(message, question, history, baseVersion, accumulated));
    }

    /** 이전 결과 금액은 기록으로 넘기지 않는다(#418). 계산 결과·설명 글은 금액 없는 안내 문장으로 바꾼다. */
    private List<HistoryMessage> history(long memberId, long beforeId) {
        String omitted = copy.get("chat.historyResultOmitted");
        return messages.findCompletedBefore(memberId, beforeId, HISTORY_LIMIT).stream()
                .map(row -> row.toHistory(omitted))
                .toList();
    }

    private AiChatRequest buildRequest(Snapshot snapshot) {
        if (snapshot.message().isExplanation()) {
            return buildExplainRequest(snapshot);
        }
        return buildQuestionRequest(snapshot);
    }

    private AiChatRequest buildQuestionRequest(Snapshot snapshot) {
        ChatMessage message = snapshot.message();
        if (snapshot.question() == null) {
            throw new IllegalStateException("질문 행을 찾지 못함");
        }
        Optional<PlanContextSnapshot> plan = planContexts.findForCurrentPlan(message.memberId());
        Scenario baseline = plan.map(PlanContextSnapshot::baselineScenario).orElse(Scenario.AVG);
        return new AiChatRequest(message.memberId(), message.id(), "QUESTION", snapshot.question().content(),
                baseline.name(), null, plan.map(PlanContextSnapshot::body).orElse(null), snapshot.history(),
                snapshot.basePlanVersionId(), AccumulatedConditions.toRequest(json, snapshot.accumulated()));
    }

    private AiChatRequest buildExplainRequest(Snapshot snapshot) {
        ChatMessage message = snapshot.message();
        long calcRunId = message.calcRunId();
        Scenario baseline = Scenario.valueOf(message.baselineScenario());
        PlanContextSnapshot plan = planContexts.findForCalcRun(message.memberId(), calcRunId, baseline)
                .orElseThrow(() -> new IllegalStateException("설명할 계산 기록을 찾지 못함"));
        return new AiChatRequest(message.memberId(), message.id(), "EXPLAIN", null, baseline.name(), calcRunId,
                plan.body(), snapshot.history(), null);
    }

    /**
     * 갱신·citation·변경 목록 저장은 한 트랜잭션이다. 0행이면(그 사이 삭제·정리됨) 답을 버리고 citation도 넣지 않는다.
     * 다시 계산한 비교(RECALC_RESULT)면 그 계산의 비교 기준(쌓인 조건의 최종 값)을 남기고 이번 변경을 목록에 덧붙인다(#418).
     * 되묻기(CLARIFY, 계산 엔진 422 포함)·일반 답은 목록을 건드리지 않는다.
     */
    private void complete(Snapshot snapshot, AiChatAnswer answer) {
        ChatMessage message = snapshot.message();
        MessageType type = MessageType.valueOf(answer.messageType());
        String turnJson = conditionChangesJson(answer.conditionChanges());
        boolean accumulates = type == MessageType.RECALC_RESULT && snapshot.basePlanVersionId() != null
                && turnJson != null;
        Map<String, JsonNode> baseline = AccumulatedConditions.fold(json, snapshot.accumulated());
        String baselineJson = accumulates && !baseline.isEmpty() ? json.writeValueAsString(baseline) : null;
        Boolean saved = transactions.execute(status -> {
            int updated = messages.apply(message.complete(type, answer.content(), answer.calcRunId(),
                    answer.baselineScenario(), turnJson, baselineJson));
            if (updated == 0) {
                return false;
            }
            messages.insertCitations(message.memberId(), message.id(), answer.citationChunkIds());
            if (accumulates) {
                appendChanges(snapshot, baseline, turnJson);
            }
            return true;
        });
        if (Boolean.TRUE.equals(saved)) {
            LOG.info("chat.answer.completed messageId={} type={}", message.id(), answer.messageType());
        } else {
            LOG.info("chat.answer.discarded messageId={} memberId={}", message.id(), message.memberId());
        }
    }

    private void appendChanges(Snapshot snapshot, Map<String, JsonNode> baseline, String turnJson) {
        ChatMessage message = snapshot.message();
        long planVersionId = snapshot.basePlanVersionId();
        plans.findVersion(message.memberId(), planVersionId).ifPresent(plan -> {
            Map<String, JsonNode> turn = StoredConditionChanges.parse(json, message.id(), turnJson);
            conditionChanges.append(message.memberId(), message.id(), planVersionId,
                    AccumulatedConditions.newEntries(json, baseline, turn, plan));
        });
    }

    /** ai-server의 snake_case 조건 키를 camelCase로 바꾼다. 허용 7키(D-097, #345) 밖의 키는 그대로 둔다. */
    private String conditionChangesJson(JsonNode changes) {
        if (changes == null) {
            return null;
        }
        ObjectNode converted = json.createObjectNode();
        changes.properties().forEach(entry ->
                converted.set(CONDITION_KEYS.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue()));
        return json.writeValueAsString(converted);
    }

    private void fail(ChatMessage message) {
        try {
            transactions.executeWithoutResult(status ->
                    messages.apply(message.fail(copy.get("chat.failed"))));
        } catch (RuntimeException e) {
            // 이벤트를 미완료로 남겨 재기동 때 다시 처리하게 한다
            LOG.error("chat.answer.failUpdateFailed messageId={}", message.id(), e);
            throw e;
        }
    }

    /** 호출 전에 읽어 둔 값. 설명(EXPLAIN)이면 question이 없다. */
    private record Snapshot(ChatMessage message, QuestionMessage question, List<HistoryMessage> history,
            Long basePlanVersionId, List<ConditionChange> accumulated) {
    }
}
