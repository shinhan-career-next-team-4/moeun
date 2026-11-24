package com.moeun.backend.chat;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.moeun.backend.chat.domain.MessageStatus;
import com.moeun.backend.chat.domain.MessageType;
import com.moeun.backend.chat.domain.Role;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/** chat 모듈 구조 규칙(#312). 스프링 컨텍스트 없이 돈다. Gradle 테스트의 작업 디렉터리는 backend/다. */
class ChatStructureTest {

    static final JavaClasses CHAT = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.moeun.backend.chat");

    @Test
    void roleMatchesSchemaCheck() throws IOException {
        assertThat(checkValues("ck_cm_role", "role")).containsExactlyElementsOf(names(Role.values()));
    }

    @Test
    void messageTypeMatchesSchemaCheck() throws IOException {
        assertThat(checkValues("ck_cm_type", "message_type")).containsExactlyElementsOf(names(MessageType.values()));
    }

    @Test
    void messageStatusMatchesSchemaCheck() throws IOException {
        assertThat(checkValues("ck_cm_status", "status")).containsExactlyElementsOf(names(MessageStatus.values()));
    }

    /** SQL 구현(JdbcClient·JPA)은 chat.infrastructure에만 있다. domain에는 인터페이스만 둔다. */
    @Test
    void sqlImplementationLivesInInfrastructure() {
        noClasses().that().resideOutsideOfPackage("com.moeun.backend.chat.infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..",
                        "org.springframework.data..", "jakarta.persistence..")
                .check(CHAT);
        assertThat(CHAT.stream().filter(type -> type.getPackageName().equals("com.moeun.backend.chat.infrastructure")))
                .isNotEmpty();
    }

    @Test
    void architectureStoreHasNoChatViolations() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/test/resources/archunit_store"))) {
            assertThat(files.map(ChatStructureTest::read)).noneMatch(text -> text.contains("com.moeun.backend.chat."));
        }
    }

    private static List<String> checkValues(String constraint, String column) throws IOException {
        Matcher matcher = Pattern.compile(constraint + "\\s+CHECK \\(" + column + " IN \\(([^)]*)\\)\\)")
                .matcher(Files.readString(Path.of("src/main/resources/db/schema.sql")));
        assertThat(matcher.find()).as(constraint).isTrue();
        return Arrays.stream(matcher.group(1).split(",")).map(value -> value.trim().replace("'", "")).toList();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
