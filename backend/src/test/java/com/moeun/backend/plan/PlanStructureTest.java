package com.moeun.backend.plan;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.moeun.backend.plan.application.PlanInputValidator;
import com.moeun.backend.plan.domain.BaselineScenario;
import com.moeun.backend.plan.domain.Plan;
import com.moeun.backend.plan.domain.PlanConditions;
import com.moeun.backend.plan.domain.PlanSource;
import com.moeun.backend.plan.domain.PlanVersion;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import jakarta.persistence.Version;

/** plan 모듈 구조 규칙(#310). 스프링 컨텍스트 없이 돈다. Gradle 테스트의 작업 디렉터리는 backend/다. */
class PlanStructureTest {

    /** checkstyle ParameterNumber의 기본 최댓값. */
    static final int MAX_PARAMETERS = 7;

    @Test
    void entityConstructorsStayWithinCheckstyleDefaultParameterCount() {
        Stream.of(Plan.class, PlanVersion.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredConstructors()))
                .forEach(constructor -> assertThat(constructor.getParameterCount())
                        .as(describe(constructor)).isLessThanOrEqualTo(MAX_PARAMETERS));
    }

    @Test
    void planSourcesDoNotSuppressCheckstyle() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/moeun/backend/plan"))) {
            List<Path> suppressing = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> read(file).contains("checkstyle:"))
                    .toList();
            assertThat(suppressing).isEmpty();
        }
    }

    @Test
    void sourceMatchesSchemaCheck() throws IOException {
        assertThat(checkValues("ck_pv_source")).containsExactlyElementsOf(names(PlanSource.values()));
    }

    @Test
    void baselineScenarioMatchesSchemaCheck() throws IOException {
        assertThat(checkValues("ck_plan_baseline")).containsExactlyElementsOf(names(BaselineScenario.values()));
    }

    @Test
    void revisionIsJpaVersion() throws NoSuchFieldException {
        assertThat(Plan.class.getDeclaredField("revision").isAnnotationPresent(Version.class)).isTrue();
    }

    /** PUT /api/plan의 JSON 해석은 web에서 끝난다. 저장 커맨드와 그 검증은 Jackson을 모른다. */
    @Test
    void saveCommandAndValidationDoNotKnowJson() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("tools.jackson..", "com.fasterxml.jackson..")
                .check(new ClassFileImporter().importClasses(PlanInput.class, PlanSaveService.class,
                        PlanInputValidator.class, PlanConditions.class, PlanVersion.class, Plan.class));
    }

    @Test
    void architectureStoreHasNoPlanViolations() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/test/resources/archunit_store"))) {
            assertThat(files.map(PlanStructureTest::read)).noneMatch(text -> text.contains("com.moeun.backend.plan."));
        }
    }

    private static List<String> checkValues(String constraint) throws IOException {
        Matcher matcher = Pattern.compile(constraint + "\\s+CHECK \\(\\w+ IN \\(([^)]*)\\)\\)")
                .matcher(Files.readString(Path.of("src/main/resources/db/schema.sql")));
        assertThat(matcher.find()).as(constraint).isTrue();
        return Arrays.stream(matcher.group(1).split(",")).map(value -> value.trim().replace("'", "")).toList();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static String describe(Constructor<?> constructor) {
        return constructor.getDeclaringClass().getSimpleName() + " 생성자 인자 수";
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
