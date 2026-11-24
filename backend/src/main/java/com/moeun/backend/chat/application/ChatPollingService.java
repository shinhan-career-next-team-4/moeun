package com.moeun.backend.chat.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.calc.CalcRunEtfQuery;
import com.moeun.backend.chat.domain.ChatMessageRepository;
import com.moeun.backend.chat.domain.MessageRow;
import com.moeun.backend.config.copy.CopyBook;
import com.moeun.backend.rag.Citation;
import com.moeun.backend.rag.CitationQueryService;

/**
 * F-2 대화 복원(afterId 없음)과 폴링(afterId 있음). 오래된 PENDING을 먼저 정리하므로 읽기 전용 트랜잭션이 아니다.
 * 정리·조회·출처 조회가 한 트랜잭션이라 중간에 실패하면 정리도 함께 되돌아간다.
 */
@Service
public class ChatPollingService {

    static final int RESTORE_LIMIT = 50;

    private final ChatMessageRepository messages;
    private final StalePendingCleaner cleaner;
    private final CitationQueryService citations;
    private final CopyBook copy;
    private final CalcRunEtfQuery etfRuns;

    ChatPollingService(ChatMessageRepository messages, StalePendingCleaner cleaner, CitationQueryService citations,
            CopyBook copy, CalcRunEtfQuery etfRuns) {
        this.messages = messages;
        this.cleaner = cleaner;
        this.citations = citations;
        this.copy = copy;
        this.etfRuns = etfRuns;
    }

    @Transactional
    public PollResult poll(long memberId, Long afterId) {
        cleaner.failStale(memberId);
        // 플래그를 목록보다 먼저 구한다. 사이에 답변이 끝나면 "플래그 true·행 COMPLETED"가 되어 한 번 더 폴링할 뿐이지만,
        // 반대 순서면 "행 PENDING·hasPending=false"가 되어 프론트가 폴링을 멈춘다(READ COMMITTED는 문장마다 스냅샷).
        boolean hasPending = messages.existsPending(memberId);
        boolean answerPending = messages.existsPendingAnswer(memberId);
        List<MessageRow> rows = afterId == null
                ? messages.findLatest(memberId, RESTORE_LIMIT) : messages.findAfter(memberId, afterId);
        List<Long> completedIds = rows.stream().filter(row -> "COMPLETED".equals(row.status()))
                .map(MessageRow::id).toList();
        Map<Long, List<Citation>> found = citations.findByMessageIds(completedIds);
        Set<Long> runsWithEtf = etfRuns.findRunsUsingEtf(rows.stream().filter(ChatPollingService::isExplanation)
                .map(MessageRow::calcRunId).filter(Objects::nonNull).distinct().toList());
        List<PolledMessage> shown = rows.stream().map(row -> show(row, found, runsWithEtf)).toList();
        return new PollResult(shown, hasPending, answerPending);
    }

    /** PENDING은 유형·본문을 가리고, FAILED는 저장된 본문과 무관하게 현재 실패 문구표를 낸다. 출처는 COMPLETED에만 붙는다. */
    private PolledMessage show(MessageRow row, Map<Long, List<Citation>> found, Set<Long> runsWithEtf) {
        return switch (row.status()) {
            case "PENDING" -> new PolledMessage(row.id(), row.role(), null, row.status(), null, List.of(),
                    row.createdAt(), null);
            case "FAILED" -> new PolledMessage(row.id(), row.role(), "FAILURE", row.status(),
                    copy.get("chat.failed"), List.of(), row.createdAt(), null);
            default -> new PolledMessage(row.id(), row.role(), row.messageType(), row.status(), row.content(),
                    found.getOrDefault(row.id(), List.of()), row.createdAt(), etfIncluded(row, runsWithEtf));
        };
    }

    /** 완료된 결과 설명만 그 계산 기록의 ETF 사용 여부를 싣는다. 설명은 calc_run_id가 늘 있다(ck_cm_explain). */
    private static Boolean etfIncluded(MessageRow row, Set<Long> runsWithEtf) {
        return isExplanation(row) ? runsWithEtf.contains(row.calcRunId()) : null;
    }

    private static boolean isExplanation(MessageRow row) {
        return "COMPLETED".equals(row.status()) && "EXPLANATION".equals(row.messageType());
    }
}
