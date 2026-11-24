package com.moeun.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.moeun.backend.config.TestcontainersConfig;

/**
 * rag 공개 출처 조회 서비스 (#42, 사용자 결정 B1: 입력은 메시지 id 목록). 메시지마다 출처를 순위 순서로,
 * 제목(rag_document.title)·URL(rag_document.source_url)·기관(source_org.name)으로 돌려준다.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class CitationQueryServiceTest {

    @Autowired
    CitationQueryService citations;

    @Autowired
    JdbcClient jdbc;

    long member;

    @BeforeEach
    void prepare() {
        jdbc.sql("DELETE FROM chat_message").update();
        member = jdbc.sql("INSERT INTO member (login_id, password_hash) VALUES ('rag-citation-test', 'x') "
                        + "ON CONFLICT ON CONSTRAINT uq_member_login_id DO UPDATE SET password_hash = 'x' "
                        + "RETURNING id")
                .query(Long.class).single();
    }

    long message(String content) {
        return jdbc.sql("INSERT INTO chat_message (member_id, role, message_type, status, content) "
                        + "VALUES (?, 'ASSISTANT', 'ANSWER', 'COMPLETED', ?) RETURNING id")
                .params(member, content).query(Long.class).single();
    }

    long sourceChunk(String orgName, String title, String url) {
        long org = jdbc.sql("INSERT INTO source_org (name, org_type) VALUES (?, 'ASSET_MANAGER') "
                        + "ON CONFLICT ON CONSTRAINT uq_source_org_name DO UPDATE SET org_type = EXCLUDED.org_type "
                        + "RETURNING id")
                .param(orgName).query(Long.class).single();
        long document = jdbc.sql("INSERT INTO rag_document (doc_type, source_org_id, title, source_url, "
                        + "content_sha256) VALUES ('DISCLOSURE', ?, ?, ?, ?) "
                        + "ON CONFLICT ON CONSTRAINT uq_rag_document_rev DO UPDATE SET title = EXCLUDED.title "
                        + "RETURNING id")
                .params(org, title, url, "e".repeat(64)).query(Long.class).single();
        return jdbc.sql("INSERT INTO rag_chunk (document_id, chunk_no, content) VALUES (?, 1, 'chunk') "
                        + "ON CONFLICT ON CONSTRAINT uq_rag_chunk_doc_no DO UPDATE SET content = EXCLUDED.content "
                        + "RETURNING id")
                .param(document).query(Long.class).single();
    }

    /** chunkId와 같은 문서의 다른 청크. */
    long otherChunkOfSameDocument(long chunkId, int chunkNo) {
        return jdbc.sql("INSERT INTO rag_chunk (document_id, chunk_no, content) "
                        + "SELECT document_id, ?, 'other chunk' FROM rag_chunk WHERE id = ? "
                        + "ON CONFLICT ON CONSTRAINT uq_rag_chunk_doc_no DO UPDATE SET content = EXCLUDED.content "
                        + "RETURNING id")
                .params(chunkNo, chunkId).query(Long.class).single();
    }

    void cite(long messageId, int rank, long chunkId) {
        jdbc.sql("INSERT INTO chat_message_citation (message_id, rank, chunk_id) VALUES (?, ?, ?)")
                .params(messageId, rank, chunkId).update();
    }

    @Test
    void returnsCitationsPerMessageInRankOrder() {
        long chunkA = sourceChunk("rag-org-A", "A 운용사 간이투자설명서", "https://example.com/rag/a");
        long chunkB = sourceChunk("rag-org-B", "B 운용사 투자설명서", "https://example.com/rag/b");
        long first = message("첫 답변");
        long second = message("둘째 답변");
        long plain = message("출처 없는 답변");
        cite(first, 2, chunkA);   // 순위 2를 먼저 넣어도 순위 순으로
        cite(first, 1, chunkB);
        cite(second, 1, chunkA);

        Map<Long, List<Citation>> found = citations.findByMessageIds(List.of(first, second, plain));

        assertThat(found.get(first)).containsExactly(
                new Citation("B 운용사 투자설명서", "https://example.com/rag/b", "rag-org-B"),
                new Citation("A 운용사 간이투자설명서", "https://example.com/rag/a", "rag-org-A"));
        assertThat(found.get(second)).containsExactly(
                new Citation("A 운용사 간이투자설명서", "https://example.com/rag/a", "rag-org-A"));
        assertThat(found.getOrDefault(plain, List.of())).isEmpty();
    }

    /** 인용은 청크 단위지만 화면의 출처는 문서 단위다(#375). 같은 문서는 처음 인용한 순위에 한 번만 나온다. */
    @Test
    void chunksOfSameDocumentAreReturnedOnceAtFirstRank() {
        long tigerFirst = sourceChunk("rag-org-B", "B 운용사 투자설명서", "https://example.com/rag/b");
        long tigerSecond = otherChunkOfSameDocument(tigerFirst, 2);
        long tigerThird = otherChunkOfSameDocument(tigerFirst, 3);
        long kodex = sourceChunk("rag-org-A", "A 운용사 간이투자설명서", "https://example.com/rag/a");
        long answer = message("같은 문서를 여러 번 인용한 답변");
        cite(answer, 1, tigerFirst);
        cite(answer, 2, kodex);
        cite(answer, 3, tigerSecond);
        cite(answer, 4, tigerThird);

        Map<Long, List<Citation>> found = citations.findByMessageIds(List.of(answer));

        assertThat(found.get(answer)).containsExactly(
                new Citation("B 운용사 투자설명서", "https://example.com/rag/b", "rag-org-B"),
                new Citation("A 운용사 간이투자설명서", "https://example.com/rag/a", "rag-org-A"));
    }

    /** 문서 하나만 여러 청크로 인용해도 한 건이다. 다른 메시지의 같은 문서 인용은 그 메시지에 따로 남는다. */
    @Test
    void duplicatesAreRemovedPerMessageOnly() {
        long first = sourceChunk("rag-org-B", "B 운용사 투자설명서", "https://example.com/rag/b");
        long second = otherChunkOfSameDocument(first, 2);
        long one = message("첫 답변");
        long other = message("둘째 답변");
        cite(one, 1, first);
        cite(one, 2, second);
        cite(other, 1, second);

        Map<Long, List<Citation>> found = citations.findByMessageIds(List.of(one, other));

        Citation b = new Citation("B 운용사 투자설명서", "https://example.com/rag/b", "rag-org-B");
        assertThat(found.get(one)).containsExactly(b);
        assertThat(found.get(other)).containsExactly(b);
    }

    @Test
    void onlyRequestedMessagesAreReturned() {
        long chunk = sourceChunk("rag-org-A", "A 운용사 간이투자설명서", "https://example.com/rag/a");
        long asked = message("요청한 답변");
        long notAsked = message("요청하지 않은 답변");
        cite(asked, 1, chunk);
        cite(notAsked, 1, chunk);

        Map<Long, List<Citation>> found = citations.findByMessageIds(Set.of(asked));

        assertThat(found.keySet()).containsExactly(asked);
    }

    /** 문서 비활성화는 검색 제외용이다(ERD rag_document.is_active). 이미 연결된 과거 인용은 그대로 돌려준다. */
    @Test
    void deactivatedDocumentCitationsAreStillReturned() {
        long inactive = sourceChunk("rag-org-off", "비활성 운용사 투자설명서", "https://example.com/rag/inactive");
        long active = sourceChunk("rag-org-A", "A 운용사 간이투자설명서", "https://example.com/rag/a");
        jdbc.sql("UPDATE rag_document SET is_active = FALSE WHERE id = "
                        + "(SELECT document_id FROM rag_chunk WHERE id = ?)")
                .param(inactive).update();
        long answer = message("비활성 문서를 인용한 답변");
        cite(answer, 1, inactive);
        cite(answer, 2, active);

        Map<Long, List<Citation>> found = citations.findByMessageIds(List.of(answer));

        assertThat(found.get(answer)).containsExactly(
                new Citation("비활성 운용사 투자설명서", "https://example.com/rag/inactive", "rag-org-off"),
                new Citation("A 운용사 간이투자설명서", "https://example.com/rag/a", "rag-org-A"));
    }

    @Test
    void emptyOrUnknownIdsReturnNothing() {
        assertThat(citations.findByMessageIds(List.of())).isEmpty();
        assertThat(citations.findByMessageIds(List.of(Long.MAX_VALUE))).isEmpty();
    }
}
