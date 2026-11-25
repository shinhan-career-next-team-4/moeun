package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * F-2 문서·모듈 규칙 검사 (#42, 코드 차이 X-4, 사용자 결정 "출처 조회는 rag 모듈 서비스"). 스프링 컨텍스트 없이 돈다.
 * Gradle 테스트의 작업 디렉터리는 backend/다.
 */
class ChatPollingRulesTest {

    private static final List<String> RAG_TABLES = List.of("rag_document", "rag_chunk", "source_org");

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 모듈의 Java 소스(package-info 제외). */
    private static List<Path> javaSources(String module) throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/moeun/backend/" + module))) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("package-info.java"))
                    .toList();
        }
    }

    /** 주석을 뺀 코드(소문자). 설명 문장에 테이블 이름이 나오는 것은 읽기가 아니다. */
    private static String codeWithoutComments(Path path) {
        String code = read(path).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        return code.toLowerCase(Locale.ROOT);
    }

    /** db/erd.md의 "### Q2." 절(다음 "### " 제목 전까지). */
    private static String erdQ2Section() {
        String erd = read(Path.of("../db/erd.md"));
        int start = erd.indexOf("### Q2.");
        assertThat(start).as("erd.md에 Q2 절이 있어야 해요").isNotNegative();
        int end = erd.indexOf("\n### ", start + 1);
        return end < 0 ? erd.substring(start) : erd.substring(start, end);
    }

    // ---------- AC-17 ERD Q2-b 정정 ----------

    @Test
    void erdPollingQueryUsesAfterIdOnly() {
        String q2 = erdQ2Section();

        assertThat(q2).doesNotContain("pending_ids").doesNotContain("last_seen");
        assertThat(Pattern.compile("id\\s*>\\s*\\$?after_?id", Pattern.CASE_INSENSITIVE).matcher(q2).find())
                .as("Q2-b 폴링 조건이 id > afterId여야 해요")
                .isTrue();
    }

    /** Q2의 "-- (b)" SQL 문장 자체: 회원 조건과 id > afterId 하나뿐이고 OR·ANY 조건이 없다. */
    @Test
    void erdQ2bWhereClauseIsMemberAndAfterIdOnly() {
        String q2 = erdQ2Section();
        int start = q2.indexOf("-- (b)");
        int end = q2.indexOf("-- (c)", start + 1);
        assertThat(start).as("Q2에 -- (b) 쿼리가 있어야 해요").isNotNegative();
        assertThat(end).as("Q2에 -- (c) 쿼리가 있어야 해요").isGreaterThan(start);
        String sql = q2.substring(q2.indexOf('\n', start) + 1, end);
        Matcher where = Pattern.compile("(?is)\\bWHERE\\b(.*?)(\\bORDER\\b|;)").matcher(sql);
        assertThat(where.find()).as("Q2-b에 WHERE 절이 있어야 해요: " + sql).isTrue();
        String condition = where.group(1).replaceAll("\\s+", " ").trim();

        assertThat(condition).doesNotContainIgnoringCase(" OR ").doesNotContainIgnoringCase("ANY(");
        List<String> parts = Arrays.stream(condition.split("(?i)\\bAND\\b"))
                .map(part -> part.replaceAll("[()]", " ").replaceAll("\\s+", " ").trim())
                .toList();
        assertThat(parts).as("조건은 회원과 afterId 두 개: " + condition).hasSize(2);
        // 회원 조건은 요청 회원 파라미터와의 같음 비교여야 한다(Q2-a와 같은 $user 형식). IS NOT NULL 같은 조건은 안 된다
        assertThat(parts).as("member_id = $파라미터 조건: " + condition)
                .anyMatch(part -> part.matches("(?i)member_id\\s*=\\s*\\$\\w+"));
        assertThat(parts).as("id > $afterId 조건: " + condition)
                .anyMatch(part -> part.matches("(?i)id\\s*>\\s*\\$after_?id"));
    }

    // ---------- AC-15·AC-16 출처는 rag 모듈 서비스로 조회 ----------

    @Test
    void chatModuleDoesNotReadRagTablesDirectly() throws IOException {
        for (Path source : javaSources("chat")) {
            String code = codeWithoutComments(source);
            for (String table : RAG_TABLES) {
                assertThat(code).as(source + "가 rag 테이블 " + table + "을 직접 읽어요").doesNotContain(table);
            }
        }
    }

    @Test
    void ragModuleReadsCitationSources() throws IOException {
        boolean readsDocuments = javaSources("rag").stream()
                .map(ChatPollingRulesTest::codeWithoutComments)
                .anyMatch(code -> code.contains("rag_document") || code.contains("ragdocument"));

        assertThat(readsDocuments).as("rag 모듈에 출처 문서(rag_document)를 읽는 코드가 있어야 해요").isTrue();
    }
}
