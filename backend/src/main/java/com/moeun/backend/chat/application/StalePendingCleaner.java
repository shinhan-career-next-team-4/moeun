package com.moeun.backend.chat.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.moeun.backend.chat.domain.ChatMessageRepository;
import com.moeun.backend.config.copy.CopyBook;

/**
 * 60초가 지나도록 PENDING인 그 회원의 메시지를 FAILED·FAILURE·chat.failed로 정리한다(질문·설명 공통).
 * 호출하는 쪽의 트랜잭션에 참여한다. F-1이 질문을 받기 전에 부르고, F-2·F-3도 같은 것을 쓴다.
 */
@Component
public class StalePendingCleaner {

    private static final Logger LOG = LoggerFactory.getLogger(StalePendingCleaner.class);
    static final Duration STALE_AFTER = Duration.ofSeconds(60);

    private final ChatMessageRepository messages;
    private final Clock clock;
    private final CopyBook copy;

    StalePendingCleaner(ChatMessageRepository messages, Clock clock, CopyBook copy) {
        this.messages = messages;
        this.clock = clock;
        this.copy = copy;
    }

    /** created_at이 now − 60초보다 엄격히 앞선 행만 정리한다. 정리한 행 수를 돌려준다. */
    public int failStale(long memberId) {
        Instant cutoff = ceilToMicros(clock.instant().minus(STALE_AFTER));
        OffsetDateTime threshold = OffsetDateTime.ofInstant(cutoff, clock.getZone());
        int cleaned = messages.failPendingCreatedBefore(memberId, threshold, copy.get("chat.failed"));
        if (cleaned > 0) {
            LOG.info("chat.pending.cleaned memberId={} count={}", memberId, cleaned);
        }
        return cleaned;
    }

    /**
     * created_at(timestamptz)은 마이크로초까지만 저장되고 JDBC 바인딩도 마이크로초로 반올림된다. 기준을 그대로 넘기면
     * 60초+1ns 지난 행이 기준과 같아져 엄격 비교에서 빠지므로, 마이크로초 단위로 올림해 넘긴다.
     */
    static Instant ceilToMicros(Instant instant) {
        Instant truncated = instant.truncatedTo(ChronoUnit.MICROS);
        return truncated.equals(instant) ? instant : truncated.plus(1, ChronoUnit.MICROS);
    }
}
