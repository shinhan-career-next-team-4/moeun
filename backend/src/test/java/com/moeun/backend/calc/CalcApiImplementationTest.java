package com.moeun.backend.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * planContext(PlanContextProvider)와 대화 비교 재계산(ChatComparisonQuery)은 역할별 클래스가 따로 구현한다(#311).
 * 스프링 컨텍스트 없이 돈다. Gradle 테스트의 작업 디렉터리는 backend/다.
 */
class CalcApiImplementationTest {

    static final JavaClasses CALC = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.moeun.backend.calc");

    @Test
    void eachPublicApiHasItsOwnImplementation() {
        List<JavaClass> planContext = implementationsOf(PlanContextProvider.class);
        List<JavaClass> chatComparison = implementationsOf(ChatComparisonQuery.class);

        assertThat(planContext).hasSize(1);
        assertThat(chatComparison).hasSize(1);
        assertThat(planContext.get(0)).isNotEqualTo(chatComparison.get(0));
    }

    @Test
    void noCalcClassIsNamedImpl() {
        assertThat(CALC.stream().map(JavaClass::getSimpleName)).noneMatch(name -> name.endsWith("Impl"));
    }

    @Test
    void architectureStoreHasNoCalcImplViolation() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/test/resources/archunit_store"))) {
            assertThat(files.map(CalcApiImplementationTest::read))
                    .noneMatch(text -> text.contains("com.moeun.backend.calc.") && text.contains("'Impl'"));
        }
    }

    private static List<JavaClass> implementationsOf(Class<?> api) {
        return CALC.stream()
                .filter(type -> !type.isInterface() && type.isAssignableTo(api))
                .toList();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
