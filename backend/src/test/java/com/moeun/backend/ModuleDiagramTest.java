package com.moeun.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;
import org.springframework.modulith.docs.Documenter.DiagramOptions;
import org.springframework.modulith.docs.Documenter.DiagramOptions.DiagramStyle;
import org.springframework.modulith.docs.Documenter.Options;

/**
 * docs/module-dependencies.puml(ARCHITECTURE.md가 가리키는 모듈 의존 그림)이 Modulith Documenter가 지금 코드로 만든
 * 그림과 같은지 본다(#311). 다르면 실패 메시지의 그림으로 파일을 바꾼다. Gradle 테스트의 작업 디렉터리는 backend/다.
 */
class ModuleDiagramTest {

    static final String OUTPUT = "build/module-diagram";
    static final Path DOCUMENTED = Path.of("docs/module-dependencies.puml");

    @Test
    void documentedModuleDiagramIsCurrent() throws IOException {
        new Documenter(ApplicationModules.of(BackendApplication.class), Options.defaults().withOutputFolder(OUTPUT))
                .writeModulesAsPlantUml(DiagramOptions.defaults().withStyle(DiagramStyle.UML));
        String generated = Files.readString(Path.of(OUTPUT, "components.puml"));

        assertThat(normalize(Files.readString(DOCUMENTED)))
                .as("%s를 아래 그림으로 바꾼다:%n%s", DOCUMENTED, generated)
                .isEqualTo(normalize(generated));
    }

    /** Documenter는 관계 줄(-->)의 순서를 매번 같게 내지 않는다. 관계 줄만 정렬하고 나머지는 순서대로 본다. */
    private static List<String> normalize(String diagram) {
        List<String> lines = diagram.strip().lines().map(String::stripTrailing).toList();
        return Stream.concat(
                lines.stream().filter(line -> !line.contains(" --> ")),
                lines.stream().filter(line -> line.contains(" --> ")).sorted())
                .toList();
    }
}
