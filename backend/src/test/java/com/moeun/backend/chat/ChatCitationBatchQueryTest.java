package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.servlet.ServletException;
import tools.jackson.databind.JsonNode;

/**
 * F-2 출처 일괄 조회와 복원의 DB 측 50개 제한 (#42, ERD Q2-c, C12b-5). DataSource가 내준 연결에서 준비·실행된 SQL 문장과
 * 각 조회의 행 수(앱이 읽은 행, DB가 돌려준 전체 행)를 기록해 센다(JdbcClient·JPA 등 구현 방식과 무관).
 */
class ChatCitationBatchQueryTest extends ChatIntegrationTestSupport {

    static final List<String> EXECUTED_SQL = new CopyOnWriteArrayList<>();
    static final List<FetchedRows> FETCHED = new CopyOnWriteArrayList<>();
    static final List<String> SOURCE_TABLES =
            List.of("chat_message_citation", "rag_chunk", "rag_document", "source_org");
    static final Pattern READS_CHAT_MESSAGE = Pattern.compile("\\bfrom\\s+chat_message\\b");

    /**
     * 조회 한 번(SQL). rows: 앱이 next()로 읽어 간 행 수. returned: DB가 그 조회로 돌려준 전체 행 수(닫힐 때 남은 행까지
     * 끝까지 읽어 센다). exhausted: 결과 끝까지 셌는지(false면 returned를 믿을 수 없다).
     */
    record FetchedRows(String sql, AtomicInteger rows, AtomicInteger returned, AtomicBoolean exhausted) {
    }

    /**
     * 연결에서 일어난 일 하나. kind: sql(문장 준비·실행) / commit / rollback. connection: DataSource가 내준 연결의 번호
     * (한 트랜잭션은 한 연결을 쓴다). autoCommit·readOnly: 그 순간 연결의 상태(자동 커밋이 꺼져 있으면 트랜잭션 안).
     */
    record ConnectionEvent(int connection, String kind, String sql, boolean autoCommit, boolean readOnly) {
    }

    /** DataSource가 내준 연결 하나와 그 번호. */
    record ConnectionRef(int id, Connection connection) {
    }

    static final List<ConnectionEvent> EVENTS = new CopyOnWriteArrayList<>();
    static final AtomicInteger CONNECTIONS = new AtomicInteger();
    static final Pattern UPDATES_CHAT_MESSAGE = Pattern.compile("^\\s*update\\s+chat_message\\b");

    /**
     * 조회 실패 주입. 0이 아니면, 정리 UPDATE(첫 UPDATE chat_message)가 나간 뒤 chat_message를 읽는 SQL 중
     * 이 번호(1부터)의 SQL을 준비·실행할 때 SQLException을 던진다.
     */
    static final AtomicInteger FAIL_READ_AFTER_CLEANUP = new AtomicInteger();
    static final AtomicBoolean CLEANUP_SEEN = new AtomicBoolean();
    static final AtomicInteger READS_AFTER_CLEANUP = new AtomicInteger();
    static final AtomicBoolean READ_FAILURE_INJECTED = new AtomicBoolean();

    @Autowired
    PlatformTransactionManager transactionManager;

    static void clearRecords() {
        EXECUTED_SQL.clear();
        FETCHED.clear();
        EVENTS.clear();
    }

    static long queriesMentioning(String table) {
        return EXECUTED_SQL.stream()
                .filter(sql -> sql.toLowerCase(Locale.ROOT).contains(table))
                .count();
    }

    /** 출처 경로의 테이블(연결·청크·문서·기관) 중 하나라도 참조하는 SQL 수. */
    static long sourcePathQueries() {
        return EXECUTED_SQL.stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .filter(sql -> SOURCE_TABLES.stream().anyMatch(sql::contains))
                .count();
    }

    static long ragDocumentQueries() {
        return EXECUTED_SQL.stream()
                .filter(sql -> sql.toLowerCase(Locale.ROOT).contains("rag_document"))
                .count();
    }

    JsonNode poll(Long afterId) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/chat/messages").cookie(cookie);
        if (afterId != null) {
            request.param("afterId", String.valueOf(afterId));
        }
        String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
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
                .params(org, title, url, "d".repeat(64)).query(Long.class).single();
        return jdbc.sql("INSERT INTO rag_chunk (document_id, chunk_no, content) VALUES (?, 1, 'chunk') "
                        + "ON CONFLICT ON CONSTRAINT uq_rag_chunk_doc_no DO UPDATE SET content = EXCLUDED.content "
                        + "RETURNING id")
                .param(document).query(Long.class).single();
    }

    void cite(long messageId, int rank, long chunkId) {
        jdbc.sql("INSERT INTO chat_message_citation (message_id, rank, chunk_id) VALUES (?, ?, ?)")
                .params(messageId, rank, chunkId).update();
    }

    /** 기록기가 실제로 SQL을 잡는지 먼저 확인한다. 잡지 못하면 아래 단언이 아무것도 검증하지 못한다. */
    void assertRecorderWorks() {
        long stored = countRows(memberId);
        clearRecords();
        jdbc.sql("SELECT id FROM chat_message WHERE member_id = ? ORDER BY id").param(memberId)
                .query(Long.class).list();
        assertThat(EXECUTED_SQL).as("SQL 기록기 자체 점검").hasSize(1);
        assertThat(FETCHED).as("행 수 기록기 자체 점검").hasSize(1);
        assertThat(FETCHED.get(0).rows().get()).as("행 수 기록기 자체 점검").isEqualTo((int) stored);
        assertThat(FETCHED.get(0).returned().get()).as("행 수 기록기 자체 점검").isEqualTo((int) stored);

        // 앱이 1행만 읽고 닫아도 DB가 돌려준 전체 행 수를 센다(닫을 때 남은 행을 끝까지 읽음)
        clearRecords();
        jdbc.sql("SELECT id FROM chat_message WHERE member_id = ? ORDER BY id").param(memberId)
                .query((ResultSetExtractor<Boolean>) rows -> rows.next());
        assertThat(FETCHED).as("남은 행 기록기 자체 점검").hasSize(1);
        assertThat(FETCHED.get(0).rows().get()).as("앱이 읽은 행").isEqualTo(Math.min(1, (int) stored));
        assertThat(FETCHED.get(0).returned().get()).as("DB가 돌려준 행").isEqualTo((int) stored);
        assertThat(FETCHED.get(0).exhausted().get()).as("끝까지 셈").isTrue();
        clearRecords();
    }

    @Test
    void citationsOfManyMessagesAreLoadedWithOneQuery() throws Exception {
        long chunkA = sourceChunk("batch-org-A", "A 운용사 간이투자설명서", "https://example.com/batch/a");
        long chunkB = sourceChunk("batch-org-B", "B 운용사 투자설명서", "https://example.com/batch/b");
        long first = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        List<Long> cited = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long answer = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "답변 " + i);
            cite(answer, 1, i % 2 == 0 ? chunkA : chunkB);
            cite(answer, 2, i % 2 == 0 ? chunkB : chunkA);
            cited.add(answer);
            insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "출처 없는 답변 " + i);
        }
        assertRecorderWorks();

        JsonNode restored = poll(null);
        assertThat(ragDocumentQueries()).as("복원 한 번에 출처 조회 SQL 수").isEqualTo(1);
        assertCitedMessagesHaveTwoCitations(restored, cited);

        clearRecords();
        JsonNode polled = poll(first);
        assertThat(ragDocumentQueries()).as("폴링 한 번에 출처 조회 SQL 수").isEqualTo(1);
        assertCitedMessagesHaveTwoCitations(polled, cited);
    }

    /**
     * 출처 경로의 SQL(연결·청크·문서·기관 테이블 각각, 그리고 요청 전체)의 수는 출처가 붙은 메시지 수와 무관하다.
     * 연결·문서는 한 번에 읽고 기관이나 청크만 메시지마다 따로 읽는 구현도 여기서 걸린다.
     */
    @Test
    void citationQueryCountDoesNotGrowWithCitedMessages() throws Exception {
        long chunkA = sourceChunk("batch-org-A", "A 운용사 간이투자설명서", "https://example.com/batch/a");
        long chunkB = sourceChunk("batch-org-B", "B 운용사 투자설명서", "https://example.com/batch/b");
        long chunkC = sourceChunk("batch-org-C", "C 운용사 투자설명서", "https://example.com/batch/c");
        long start = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        long one = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "답변");
        cite(one, 1, chunkA);
        poll(start);   // 첫 요청에만 있는 준비 작업을 빼고 잰다
        assertRecorderWorks();

        poll(start);
        long totalWithOne = EXECUTED_SQL.size();
        long sourceWithOne = sourcePathQueries();
        List<Long> perTableWithOne = SOURCE_TABLES.stream().map(ChatCitationBatchQueryTest::queriesMentioning).toList();

        for (int i = 0; i < 6; i++) {
            long answer = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "답변 " + i);
            cite(answer, 1, i % 2 == 0 ? chunkB : chunkC);
            cite(answer, 2, chunkA);
        }
        clearRecords();
        JsonNode body = poll(start);

        assertThat(body.get("messages").size()).isEqualTo(7);
        for (JsonNode message : body.get("messages")) {
            assertThat(message.get("citations").size()).isEqualTo(message.get("id").asLong() == one ? 1 : 2);
        }
        assertThat(sourcePathQueries()).as("출처 경로 SQL 수").isEqualTo(sourceWithOne);
        assertThat(SOURCE_TABLES.stream().map(ChatCitationBatchQueryTest::queriesMentioning).toList())
                .as("테이블별 SQL 수 " + SOURCE_TABLES).isEqualTo(perTableWithOne);
        assertThat(queriesMentioning("rag_document")).as("rag_document 조회 수").isEqualTo(1);
        assertThat((long) EXECUTED_SQL.size()).as("요청 전체 SQL 수").isEqualTo(totalWithOne);
    }

    /**
     * 복원은 DB에서 최근 50개만 읽는다(키셋 DESC LIMIT 50 뒤 뒤집기). chat_message를 읽는 어떤 조회도 DB가 50행을 넘게
     * 돌려주지 않는다. 더 많이(전부, LIMIT 100 등) 읽어 앱에서 자르거나, 제한 없이 조회한 뒤 앱이 50행만 읽고 닫아도 걸린다.
     */
    @Test
    void restoreLimitsRowsInTheDatabase() throws Exception {
        for (int i = 0; i < 55; i++) {
            insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문 " + i);
        }
        assertRecorderWorks();

        JsonNode body = poll(null);

        assertThat(body.get("messages").size()).isEqualTo(50);
        List<FetchedRows> listReads = FETCHED.stream()
                .filter(read -> READS_CHAT_MESSAGE.matcher(normalized(read.sql())).find())
                .toList();
        assertThat(listReads).as("chat_message를 읽는 조회").isNotEmpty();
        assertThat(listReads).allSatisfy(read -> {
            assertThat(read.exhausted().get()).as("결과 끝까지 셈: " + read.sql()).isTrue();
            assertThat(read.returned().get()).as("DB가 돌려준 행 수: " + read.sql()).isLessThanOrEqualTo(50);
        });
        assertThat(listReads).anySatisfy(read -> assertThat(read.returned().get()).isEqualTo(50));
    }

    /**
     * AC-13 순서(사용자 확정 B1): 복원·폴링 모두 오래된 PENDING 정리 UPDATE가 chat_message를 읽는 모든 실제 조회(메시지 목록·
     * 대기 플래그 등)보다 먼저, 같은 연결·같은 쓰기 가능 트랜잭션(자동 커밋 꺼짐, 읽기 전용 아님, 사이에 커밋·롤백 없음)에서
     * 실행된다. 그래서 정리된 행이 같은 응답에 FAILED로 실린다. 목록을 정리보다 먼저(또는 트랜잭션 밖에서) 읽고 응답을
     * 조립할 때 상태를 덮어쓰는 구현은 여기서 걸린다.
     */
    @Test
    void cleanupRunsBeforeMessageListInTheSameWritableTransaction() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");

        // 연결 기록기 자체 점검: 트랜잭션 안의 SQL은 자동 커밋이 꺼진 채 기록되고, 끝의 커밋도 같은 연결에서 잡힌다
        clearRecords();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                jdbc.sql("SELECT id FROM chat_message WHERE id = ?").param(question).query(Long.class).list());
        assertThat(EVENTS).as("연결 기록기 자체 점검").extracting(ConnectionEvent::kind).contains("sql", "commit");
        ConnectionEvent probe = EVENTS.stream().filter(event -> "sql".equals(event.kind())).findFirst().orElseThrow();
        assertThat(probe.autoCommit()).as("트랜잭션 안 SQL은 자동 커밋 꺼짐").isFalse();
        assertThat(EVENTS).as("커밋은 같은 연결")
                .anyMatch(event -> "commit".equals(event.kind()) && event.connection() == probe.connection());

        for (Long afterId : new Long[] {null, question}) {
            long stale = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                    now.minus(Duration.ofSeconds(61)));
            assertRecorderWorks();

            JsonNode body = poll(afterId);
            List<ConnectionEvent> events = List.copyOf(EVENTS);
            String path = "afterId=" + afterId + " " + events;

            JsonNode cleaned = null;
            for (JsonNode message : body.get("messages")) {
                if (message.get("id").asLong() == stale) {
                    cleaned = message;
                }
            }
            assertThat(cleaned).as(path + " 정리된 행이 같은 응답에 있다").isNotNull();
            assertThat(cleaned.get("status").asString()).as(path).isEqualTo("FAILED");
            assertThat(cleaned.get("messageType").asString()).as(path).isEqualTo("FAILURE");
            assertThat(body.get("hasPending").asBoolean()).as(path + " 정리된 행은 대기에 안 잡힌다").isFalse();

            int cleanup = -1;
            List<Integer> reads = new ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                ConnectionEvent event = events.get(i);
                if (!"sql".equals(event.kind())) {
                    continue;
                }
                String sql = normalized(event.sql());
                if (cleanup < 0 && UPDATES_CHAT_MESSAGE.matcher(sql).find()) {
                    cleanup = i;
                } else if (READS_CHAT_MESSAGE.matcher(sql).find()) {
                    reads.add(i);
                }
            }
            assertThat(cleanup).as(path + " 정리 UPDATE 실행").isNotNegative();
            ConnectionEvent update = events.get(cleanup);
            assertThat(update.autoCommit()).as(path + " 정리는 트랜잭션 안(자동 커밋 꺼짐)").isFalse();
            assertThat(update.readOnly()).as(path + " 정리 트랜잭션은 읽기 전용 아님").isFalse();
            assertThat(reads).as(path + " chat_message를 읽는 조회(메시지 목록 포함)").isNotEmpty();
            for (int read : reads) {
                ConnectionEvent query = events.get(read);
                assertThat(read).as(path + " 정리 뒤에 조회: " + query.sql()).isGreaterThan(cleanup);
                assertThat(query.connection()).as(path + " 정리와 같은 연결: " + query.sql())
                        .isEqualTo(update.connection());
                assertThat(query.autoCommit()).as(path + " 트랜잭션 안: " + query.sql()).isFalse();
                assertThat(query.readOnly()).as(path + " 읽기 전용 아님: " + query.sql()).isFalse();
            }
            int lastRead = reads.get(reads.size() - 1);
            assertThat(events.subList(cleanup, lastRead + 1))
                    .as(path + " 정리와 조회 사이에 그 연결의 커밋·롤백이 없다(같은 트랜잭션)")
                    .noneMatch(event -> event.connection() == update.connection() && !"sql".equals(event.kind()));
        }
    }

    /**
     * AC-14 복원 경로: 복원(afterId 없음)에서도 출처 경로의 SQL(연결·청크·문서·기관 테이블 각각, 그 합, 요청 전체)의 수는
     * 출처가 붙은 메시지 수와 무관하다. 복원에서만 연결(chat_message_citation)을 메시지마다 읽는 구현도 여기서 걸린다.
     */
    @Test
    void restoreCitationQueryCountDoesNotGrowWithCitedMessages() throws Exception {
        long chunkA = sourceChunk("batch-org-A", "A 운용사 간이투자설명서", "https://example.com/batch/a");
        long chunkB = sourceChunk("batch-org-B", "B 운용사 투자설명서", "https://example.com/batch/b");
        long chunkC = sourceChunk("batch-org-C", "C 운용사 투자설명서", "https://example.com/batch/c");
        long start = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        long one = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "답변");
        cite(one, 1, chunkA);
        poll(null);   // 첫 요청에만 있는 준비 작업을 빼고 잰다
        assertRecorderWorks();

        poll(null);
        long totalWithOne = EXECUTED_SQL.size();
        long sourceWithOne = sourcePathQueries();
        List<Long> perTableWithOne = SOURCE_TABLES.stream().map(ChatCitationBatchQueryTest::queriesMentioning).toList();

        for (int i = 0; i < 6; i++) {
            long answer = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "답변 " + i);
            cite(answer, 1, i % 2 == 0 ? chunkB : chunkC);
            cite(answer, 2, chunkA);
        }
        clearRecords();
        JsonNode body = poll(null);

        assertThat(body.get("messages").size()).isEqualTo(8);
        for (JsonNode message : body.get("messages")) {
            long id = message.get("id").asLong();
            int expected = id == start ? 0 : id == one ? 1 : 2;
            assertThat(message.get("citations").size()).as("메시지 " + id + "의 출처 수").isEqualTo(expected);
        }
        assertThat(sourcePathQueries()).as("복원 출처 경로 SQL 수").isEqualTo(sourceWithOne);
        assertThat(SOURCE_TABLES.stream().map(ChatCitationBatchQueryTest::queriesMentioning).toList())
                .as("복원 테이블별 SQL 수 " + SOURCE_TABLES).isEqualTo(perTableWithOne);
        assertThat(queriesMentioning("rag_document")).as("복원 rag_document 조회 수").isEqualTo(1);
        assertThat((long) EXECUTED_SQL.size()).as("복원 요청 전체 SQL 수").isEqualTo(totalWithOne);
    }

    /**
     * AC-13 출처 조회도 정리와 같은 트랜잭션(사용자 확정 B1): 성공한 복원·폴링에서 실제 출처 조회 SQL(연결·청크·문서·기관
     * 테이블을 읽는 SQL)은 정리 UPDATE 뒤에, 정리와 같은 연결에서 자동 커밋이 꺼지고 읽기 전용이 아닌 상태로 실행되며,
     * 정리와 마지막 출처 조회 사이에 그 연결의 커밋·롤백이 없다. rag 조회를 별도 트랜잭션(새 연결, 읽기 전용 새
     * 트랜잭션, 정리 커밋 뒤)으로 실행하는 구현은 출처가 정상으로 나와도 여기서 걸린다.
     */
    @Test
    void citationLookupRunsInTheCleanupTransaction() throws Exception {
        long chunkA = sourceChunk("batch-org-A", "A 운용사 간이투자설명서", "https://example.com/batch/a");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");
        long answer = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "출처가 있는 답변");
        cite(answer, 1, chunkA);

        for (Long afterId : new Long[] {null, question}) {
            insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null, now.minus(Duration.ofSeconds(61)));
            assertRecorderWorks();

            JsonNode body = poll(afterId);
            List<ConnectionEvent> events = List.copyOf(EVENTS);
            String path = "afterId=" + afterId + " " + events;

            JsonNode cited = null;
            for (JsonNode message : body.get("messages")) {
                if (message.get("id").asLong() == answer) {
                    cited = message;
                }
            }
            assertThat(cited).as(path + " 출처가 있는 답변이 응답에 있다").isNotNull();
            assertThat(cited.get("citations").size()).as(path).isEqualTo(1);
            assertThat(cited.get("citations").get(0).get("title").asString()).as(path)
                    .isEqualTo("A 운용사 간이투자설명서");

            int cleanup = -1;
            List<Integer> sourceReads = new ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                ConnectionEvent event = events.get(i);
                if (!"sql".equals(event.kind())) {
                    continue;
                }
                String sql = normalized(event.sql());
                if (cleanup < 0 && UPDATES_CHAT_MESSAGE.matcher(sql).find()) {
                    cleanup = i;
                } else if (SOURCE_TABLES.stream().anyMatch(sql::contains)) {
                    sourceReads.add(i);
                }
            }
            assertThat(cleanup).as(path + " 정리 UPDATE 실행").isNotNegative();
            ConnectionEvent update = events.get(cleanup);
            assertThat(sourceReads).as(path + " 출처 조회 SQL").isNotEmpty();
            assertThat(sourceReads).as(path + " rag_document를 읽는 출처 조회")
                    .anyMatch(read -> normalized(events.get(read).sql()).contains("rag_document"));
            for (int read : sourceReads) {
                ConnectionEvent query = events.get(read);
                assertThat(read).as(path + " 정리 뒤 출처 조회: " + query.sql()).isGreaterThan(cleanup);
                assertThat(query.connection()).as(path + " 정리와 같은 연결: " + query.sql())
                        .isEqualTo(update.connection());
                assertThat(query.autoCommit()).as(path + " 트랜잭션 안: " + query.sql()).isFalse();
                assertThat(query.readOnly()).as(path + " 읽기 전용 아님: " + query.sql()).isFalse();
            }
            int lastRead = sourceReads.get(sourceReads.size() - 1);
            assertThat(events.subList(cleanup, lastRead + 1))
                    .as(path + " 정리와 출처 조회 사이에 그 연결의 커밋·롤백이 없다(같은 트랜잭션)")
                    .noneMatch(event -> event.connection() == update.connection() && !"sql".equals(event.kind()));
        }
    }

    /**
     * AC-13 롤백(사용자 확정 B1): 정리 UPDATE 뒤 chat_message를 읽는 실제 조회(메시지 목록·대기 플래그 등) 중 어느 하나가
     * DB 오류로 실패해도 응답은 성공(2xx)이 아니고 정리 UPDATE도 롤백된다(복원·폴링 모두). 성공한 요청에서 정리 뒤
     * chat_message 조회 수(N)를 센 다음, k = 1..N번째 조회를 차례로 실패시킨다. 그래서 메시지 목록 조회의 실패도 반드시
     * 주입된다. 목록 조회 예외만 서비스에서 잡아 실패 응답으로 바꾸고 커밋하는 구현은 여기서 걸린다.
     */
    @Test
    void messageReadFailureAfterCleanupRollsBackTheCleanup() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(now);
        long question = insertMessage(memberId, "USER", "QUESTION", "COMPLETED", "질문");

        for (Long afterId : new Long[] {null, question}) {
            String path = "afterId=" + afterId;
            insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null, now.minus(Duration.ofSeconds(61)));
            assertRecorderWorks();
            poll(afterId);
            int reads = readsAfterCleanup(List.copyOf(EVENTS));
            assertThat(reads).as(path + " 정리 뒤 chat_message 조회 수(메시지 목록 포함) " + EVENTS).isPositive();

            long stale = insertMessageAt(memberId, "ASSISTANT", "ANSWER", "PENDING", null,
                    now.minus(Duration.ofSeconds(61)));
            for (int k = 1; k <= reads; k++) {
                String at = path + " 정리 뒤 " + k + "번째 chat_message 조회 실패";
                int status = performWithReadFailure(afterId, k);

                assertThat(READ_FAILURE_INJECTED.get()).as(at + " 주입됨").isTrue();
                assertThat(status / 100).as(at + "는 성공(2xx) 응답이 아니다: " + status).isNotEqualTo(2);
                Map<String, Object> row = jdbc
                        .sql("SELECT status, message_type, content FROM chat_message WHERE id = ?")
                        .param(stale).query().singleRow();
                assertThat(row.get("status")).as(at + " 뒤 정리 UPDATE 롤백").isEqualTo("PENDING");
                assertThat(row.get("message_type")).as(at).isEqualTo("ANSWER");
                assertThat(row.get("content")).as(at).isNull();
            }
        }
    }

    /** 정리 UPDATE(첫 UPDATE chat_message) 뒤에 chat_message를 읽은 SQL 수. */
    static int readsAfterCleanup(List<ConnectionEvent> events) {
        boolean cleaned = false;
        int reads = 0;
        for (ConnectionEvent event : events) {
            if (!"sql".equals(event.kind())) {
                continue;
            }
            String sql = normalized(event.sql());
            if (!cleaned) {
                cleaned = UPDATES_CHAT_MESSAGE.matcher(sql).find();
            } else if (READS_CHAT_MESSAGE.matcher(sql).find()) {
                reads++;
            }
        }
        return reads;
    }

    /** 정리 뒤 k번째 chat_message 조회를 실패시키고 요청한다. 처리되지 않은 예외로 끝나면 -1. */
    int performWithReadFailure(Long afterId, int failingRead) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/chat/messages").cookie(cookie);
        if (afterId != null) {
            request.param("afterId", String.valueOf(afterId));
        }
        CLEANUP_SEEN.set(false);
        READS_AFTER_CLEANUP.set(0);
        READ_FAILURE_INJECTED.set(false);
        FAIL_READ_AFTER_CLEANUP.set(failingRead);
        try {
            return mockMvc.perform(request).andReturn().getResponse().getStatus();
        } catch (ServletException e) {
            return -1;
        } finally {
            FAIL_READ_AFTER_CLEANUP.set(0);
        }
    }

    static String normalized(String sql) {
        return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static void assertCitedMessagesHaveTwoCitations(JsonNode body, List<Long> cited) {
        int found = 0;
        for (JsonNode message : body.get("messages")) {
            if (cited.contains(message.get("id").asLong())) {
                assertThat(message.get("citations").size()).isEqualTo(2);
                found++;
            }
        }
        assertThat(found).isEqualTo(cited.size());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlRecorderConfig {

        /** DataSource 빈을 같은 타입의 spy로 바꿔, 내준 연결에서 준비·실행되는 SQL 문장을 기록한다. */
        @Bean
        static BeanPostProcessor dataSourceSqlRecorder() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof DataSource)) {
                        return bean;
                    }
                    return Mockito.mock(bean.getClass(), Mockito.withSettings().spiedInstance(bean)
                            .defaultAnswer(invocation -> {
                                Object result = invocation.callRealMethod();
                                if ("getConnection".equals(invocation.getMethod().getName())
                                        && result instanceof Connection connection) {
                                    return recording(connection, Connection.class, null,
                                            new ConnectionRef(CONNECTIONS.incrementAndGet(), connection),
                                            new CopyOnWriteArrayList<>(), null);
                                }
                                return result;
                            }));
                }
            };
        }

        /**
         * boundSql: 준비된 문장이면 그 SQL. executeQuery()처럼 인자 없이 실행될 때 행 수를 이 SQL에 붙인다.
         * connectionResults·statementResults: 이 연결·문장에서 나온 결과. 연결이나 문장이 닫힐 때 남은 행을 끝까지 센다.
         */
        @SuppressWarnings("unchecked")
        static <T> T recording(T target, Class<T> type, String boundSql, ConnectionRef owner,
                List<RowCounter> connectionResults, List<RowCounter> statementResults) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                    (proxy, method, args) -> invoke(proxy, target, method, args, boundSql, owner, connectionResults,
                            statementResults));
        }

        private static Object invoke(Object proxy, Object target, Method method, Object[] args, String boundSql,
                ConnectionRef owner, List<RowCounter> connectionResults, List<RowCounter> statementResults)
                throws Throwable {
            String name = method.getName();
            if ("equals".equals(name) && args != null && args.length == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(name) && (args == null || args.length == 0)) {
                return System.identityHashCode(proxy);
            }
            if ("close".equals(name)) {
                drainAll(statementResults != null ? statementResults : connectionResults);
            }
            String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : null;
            if (isSqlMethod(name) && sql != null) {
                EXECUTED_SQL.add(sql);
                EVENTS.add(eventOf(owner, "sql", sql));
                injectReadFailure(sql);
            }
            if (statementResults == null && ("commit".equals(name) || "rollback".equals(name))) {
                EVENTS.add(eventOf(owner, name, null));
            }
            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if ("createStatement".equals(name) && result instanceof Statement statement) {
                return recording(statement, Statement.class, null, owner, connectionResults, new ArrayList<>());
            }
            if ("prepareCall".equals(name) && result instanceof CallableStatement statement) {
                return recording(statement, CallableStatement.class, sql, owner, connectionResults,
                        new ArrayList<>());
            }
            if ("prepareStatement".equals(name) && result instanceof PreparedStatement statement) {
                return recording(statement, PreparedStatement.class, sql, owner, connectionResults,
                        new ArrayList<>());
            }
            if (("executeQuery".equals(name) || "getResultSet".equals(name)) && result instanceof ResultSet rows) {
                String executed = sql != null ? sql : String.valueOf(boundSql);
                FetchedRows fetched = new FetchedRows(executed, new AtomicInteger(), new AtomicInteger(),
                        new AtomicBoolean());
                FETCHED.add(fetched);
                RowCounter counter = new RowCounter(rows, fetched);
                connectionResults.add(counter);
                if (statementResults != null) {
                    statementResults.add(counter);
                }
                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                        counter);
            }
            return result;
        }

        private static void injectReadFailure(String sql) throws SQLException {
            int target = FAIL_READ_AFTER_CLEANUP.get();
            if (target == 0) {
                return;
            }
            String text = normalized(sql);
            if (!CLEANUP_SEEN.get()) {
                CLEANUP_SEEN.set(UPDATES_CHAT_MESSAGE.matcher(text).find());
                return;
            }
            if (READS_CHAT_MESSAGE.matcher(text).find() && READS_AFTER_CLEANUP.incrementAndGet() == target) {
                READ_FAILURE_INJECTED.set(true);
                throw new SQLException("정리 뒤 chat_message 조회 실패 주입: " + sql);
            }
        }

        private static ConnectionEvent eventOf(ConnectionRef owner, String kind, String sql) throws SQLException {
            Connection connection = owner.connection();
            return new ConnectionEvent(owner.id(), kind, sql, connection.getAutoCommit(), connection.isReadOnly());
        }

        private static void drainAll(List<RowCounter> results) {
            for (RowCounter counter : results) {
                try {
                    counter.drain();
                } catch (SQLException e) {
                    // 끝까지 세지 못한 결과는 exhausted=false로 남아 단언에서 걸린다
                }
            }
        }

        private static boolean isSqlMethod(String name) {
            return name.equals("prepareStatement") || name.equals("prepareCall") || name.equals("execute")
                    || name.equals("executeQuery") || name.equals("executeUpdate")
                    || name.equals("executeLargeUpdate") || name.equals("addBatch");
        }
    }

    /**
     * 결과 하나를 감싼다. next()가 true를 돌려준 횟수 = 앱이 읽어 간 행 수(rows). 결과·문장·연결 중 하나가 닫힐 때
     * 앱이 읽지 않은 남은 행을 끝까지 읽어 DB가 그 조회로 돌려준 전체 행 수(returned)를 센다. 그래서 DB에서 더 많이
     * 읽고 앱이 50행에서 멈춘 뒤 닫아도 returned에 드러난다.
     */
    static final class RowCounter implements InvocationHandler {

        private final ResultSet target;
        private final FetchedRows fetched;
        private boolean finished;

        RowCounter(ResultSet target, FetchedRows fetched) {
            this.target = target;
            this.fetched = fetched;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("equals".equals(name) && args != null && args.length == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(name) && (args == null || args.length == 0)) {
                return System.identityHashCode(proxy);
            }
            if ("close".equals(name)) {
                try {
                    drain();
                } catch (SQLException e) {
                    // 끝까지 세지 못한 결과는 exhausted=false로 남아 단언에서 걸린다
                }
            }
            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if ("next".equals(name)) {
                if (Boolean.TRUE.equals(result)) {
                    fetched.rows().incrementAndGet();
                    fetched.returned().incrementAndGet();
                } else {
                    fetched.exhausted().set(true);
                }
            }
            return result;
        }

        synchronized void drain() throws SQLException {
            if (finished) {
                return;
            }
            finished = true;
            if (fetched.exhausted().get() || target.isClosed()) {
                return;
            }
            while (target.next()) {
                fetched.returned().incrementAndGet();
            }
            fetched.exhausted().set(true);
        }
    }
}
