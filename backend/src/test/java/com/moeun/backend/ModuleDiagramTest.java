package com.moeun.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;
import org.springframework.modulith.docs.Documenter.DiagramOptions;
import org.springframework.modulith.docs.Documenter.DiagramOptions.DiagramStyle;
import org.springframework.modulith.docs.Documenter.Options;

/**
 * ARCHITECTURE.md의 모듈 의존 그림이 Modulith Documenter가 지금 코드로 만든 그림과 같은지 본다(#311).
 * 줄 순서는 보지 않는다. 다르면 실패 메시지의 그림으로 ARCHITECTURE.md의 plantuml 블록을 바꾼다. Gradle 테스트의 작업 디렉터리는 backend/다.
 */
class ModuleDiagramTest {

    static final String OUTPUT = "build/module-diagram";
    static final String BEGIN = "```plantuml\n";
    static final String END = "```";

    @Test
    void architectureDocumentHasCurrentModuleDiagram() throws IOException {
        new Documenter(ApplicationModules.of(BackendApplication.class), Options.defaults().withOutputFolder(OUTPUT))
                .writeModulesAsPlantUml(DiagramOptions.defaults().withStyle(DiagramStyle.UML));
        String generated = Files.readString(Path.of(OUTPUT, "components.puml")).strip();

        String document = Files.readString(Path.of("ARCHITECTURE.md"));
        int begin = document.indexOf(BEGIN);
        assertThat(begin).as("ARCHITECTURE.md에 plantuml 블록이 없다").isNotNegative();
        String documented = document.substring(begin + BEGIN.length(), document.indexOf(END, begin + BEGIN.length()))
                .strip();

        // Documenter는 관계 줄의 순서를 매번 같게 내지 않는다. 줄 집합으로 비교한다
        assertThat(documented.lines().sorted().toList()).as("ARCHITECTURE.md의 모듈 그림을 아래 그림으로 바꾼다:%n%s",
                generated).isEqualTo(generated.lines().sorted().toList());
    }
}
