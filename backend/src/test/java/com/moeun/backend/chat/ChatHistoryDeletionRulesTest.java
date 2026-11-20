package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 대화 삭제 수신 규칙 문서 검사 (#46, 계약 X-9·X-11). 스프링 컨텍스트 없이 돈다. */
class ChatHistoryDeletionRulesTest {

    private static String rules() throws IOException {
        return Files.readString(Path.of(".claude/rules/modulith.md"), StandardCharsets.UTF_8);
    }

    private static List<String> tableRowsMentioning(String rules, String event) {
        return rules.lines().filter(line -> line.startsWith("|") && line.contains(event)).toList();
    }

    @Test
    void eventTableListsLoginAndLogoutEventsReceivedByChatSynchronously() throws IOException {
        String rules = rules();

        for (String event : List.of("auth.MemberLoggedIn", "auth.MemberLoggedOut")) {
            List<String> rows = tableRowsMentioning(rules, "`" + event);
            assertThat(rows).as("이벤트 표의 " + event + " 행").hasSize(1);
            String[] cells = rows.get(0).split("\\|");
            assertThat(cells[2]).as(event + " 발행").contains("auth");
            assertThat(cells[3]).as(event + " 수신").contains("chat").contains("동기");
        }
    }

    @Test
    void listenerRuleHasSynchronousEventListenerException() throws IOException {
        String rules = rules();
        int section = rules.indexOf("## 이벤트");
        int table = rules.indexOf("### 이벤트 목록");

        String rule = rules.substring(section, table);
        assertThat(rule).contains("`@ApplicationModuleListener`로 한다");
        assertThat(rule.lines().filter(line -> line.contains("응답 전에 끝나야 하는 수신")
                && line.contains("동기 `@EventListener`")).count()).as("예외 1줄").isEqualTo(1);
    }
}
