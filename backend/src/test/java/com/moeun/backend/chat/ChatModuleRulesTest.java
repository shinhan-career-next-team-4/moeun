package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 모듈 의존·규칙 문서·설정 파일 검사 (#41, H-6b·C12b-10-2·C1a-10·C12b-11-1). 스프링 컨텍스트 없이 돈다.
 * Gradle 테스트의 작업 디렉터리는 backend/다.
 */
class ChatModuleRulesTest {

    /** calc는 역할별 named interface로만 쓴다(#311). */
    private static final Set<String> CHAT_DEPENDENCIES = Set.of("calc :: plan-context", "calc :: chat-comparison",
            "calc :: calc-run-etf", "rag", "plan", "auth", "config :: copy");

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static Set<String> allowedDependencies(String module) throws IOException {
        String source = read("src/main/java/com/moeun/backend/" + module + "/package-info.java");
        Matcher matcher = Pattern.compile("allowedDependencies\\s*=\\s*\\{([^}]*)}").matcher(source);
        assertThat(matcher.find()).as(module + " package-info의 allowedDependencies").isTrue();
        return Arrays.stream(matcher.group(1).split(","))
                .map(value -> value.trim().replace("\"", ""))
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toSet());
    }

    /** backend/ARCHITECTURE.md 모듈 표(#308에서 CLAUDE.md에서 옮김)에서 그 모듈 행의 '의존 가능' 칸. "—"는 빈 집합. */
    private static Set<String> documentedDependencies(String module) throws IOException {
        for (String line : read("ARCHITECTURE.md").lines().toList()) {
            List<String> cells = Arrays.stream(line.split("\\|")).map(String::trim).toList();
            if (cells.size() >= 5 && cells.get(1).equals("`" + module + "`")) {
                return Arrays.stream(cells.get(4).split(","))
                        .map(value -> value.trim().replace("`", ""))
                        .filter(value -> !value.isEmpty() && !value.equals("—"))
                        .collect(Collectors.toSet());
            }
        }
        throw new AssertionError("ARCHITECTURE.md 모듈 표에 " + module + " 행이 없어요");
    }

    private static Binder applicationYaml() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        environment.getPropertySources().addLast(sources.get(0));   // 첫 문서 = 프로필 없는 기본 설정
        return new Binder(ConfigurationPropertySources.from(environment.getPropertySources()));
    }

    // ---------- AC-22 ----------

    @Test
    void chatModuleDependenciesMatchContract() throws IOException {
        assertThat(allowedDependencies("chat")).isEqualTo(CHAT_DEPENDENCIES);
        assertThat(documentedDependencies("chat")).isEqualTo(CHAT_DEPENDENCIES);

        assertThat(allowedDependencies("plan")).isEmpty();
        assertThat(allowedDependencies("auth")).isEmpty();
        assertThat(documentedDependencies("plan")).isEmpty();
        assertThat(documentedDependencies("auth")).isEmpty();
    }

    // ---------- AC-23 ----------

    @Test
    void modulithRulesDocumentNonTransactionalHttpListener() throws IOException {
        String rules = read(".claude/rules/modulith.md");
        int eventSection = rules.indexOf("## 이벤트");
        int eventTable = rules.indexOf("### 이벤트 목록");
        assertThat(eventSection).isNotNegative();
        assertThat(eventTable).isGreaterThan(eventSection);

        assertThat(rules.substring(eventSection, eventTable)).contains("외부 HTTP를 부르는 리스너는 트랜잭션 없이 돈다");
    }

    // ---------- AC-20 ----------

    @Test
    void virtualThreadsAreEnabled() throws IOException {
        assertThat(applicationYaml().bind("spring.threads.virtual.enabled", Boolean.class).orElse(false)).isTrue();
    }

    // ---------- AC-3 ----------

    @Test
    void aiServerTimeoutDefaultsAre2And30Seconds() throws IOException {
        Binder binder = applicationYaml();

        assertThat(binder.bind("moeun.ai-server.connect-timeout", Duration.class).get())
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(binder.bind("moeun.ai-server.read-timeout", Duration.class).get())
                .isEqualTo(Duration.ofSeconds(30));
    }
}
