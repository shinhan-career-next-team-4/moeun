package com.moeun.backend.rag;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 메시지 id 목록으로 인용 출처를 한 번에 조회한다(읽기 전용). 호출하는 쪽의 트랜잭션에 참여한다. */
@Service
public class CitationQueryService {

    private final JdbcClient jdbc;

    CitationQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 메시지 id별 출처 목록(순위 오름차순). 출처가 없는 메시지는 키가 없다. 비활성 문서의 출처도 그대로 돌려준다.
     * 인용은 청크 단위지만 출처는 문서 단위라, 한 메시지 안에서 같은 출처는 처음 인용한 순위에 한 번만 둔다(#375).
     */
    public Map<Long, List<Citation>> findByMessageIds(Collection<Long> messageIds) {
        Map<Long, Set<Citation>> byMessage = new LinkedHashMap<>();
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        jdbc.sql("SELECT c.message_id, d.title, d.source_url, o.name AS org_name "
                        + "FROM chat_message_citation c "
                        + "JOIN rag_chunk ch ON ch.id = c.chunk_id "
                        + "JOIN rag_document d ON d.id = ch.document_id "
                        + "JOIN source_org o ON o.id = d.source_org_id "
                        + "WHERE c.message_id IN (:ids) ORDER BY c.message_id, c.rank")
                .param("ids", messageIds)
                .query((rs, row) -> Map.entry(rs.getLong("message_id"),
                        new Citation(rs.getString("title"), rs.getString("source_url"), rs.getString("org_name"))))
                .list()
                .forEach(entry -> byMessage.computeIfAbsent(entry.getKey(), key -> new LinkedHashSet<>())
                        .add(entry.getValue()));
        Map<Long, List<Citation>> result = new LinkedHashMap<>();
        byMessage.forEach((messageId, found) -> result.put(messageId, List.copyOf(found)));
        return result;
    }
}
