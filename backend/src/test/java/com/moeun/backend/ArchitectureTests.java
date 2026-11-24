package com.moeun.backend;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.moeun.backend.archfixture.application.SampleServiceImpl;
import com.moeun.backend.archfixture.domain.JsonParsingRule;
import com.moeun.backend.archfixture.domain.LayerDependentRule;
import com.moeun.backend.archfixture.domain.SampleRepository;
import com.moeun.backend.archfixture.domain.SpringDataSortRule;
import com.moeun.backend.archfixture.domain.SpringDependentRule;
import com.moeun.backend.archfixture.web.SampleController;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

/**
 * 모듈 내부 책임 규칙(ARCHITECTURE.md) 검증. 스프링 컨텍스트·Docker 없이 돈다.
 * 기존 위반은 src/test/resources/archunit_store에 동결되어 있고 새 위반만 실패한다.
 * 위반을 고치면 저장소에서 저절로 빠진다. 저장소를 손으로 늘리지 않는다.
 */
class ArchitectureTests {

    static final String DOMAIN = "com.moeun.backend..domain..";

    static final ArchRule DOMAIN_DEPENDS_ONLY_ON_PERSISTENCE_SPRING = noClasses()
        .that().resideInAPackage(DOMAIN)
        .should().dependOnClassesThat(resideInAPackage("org.springframework..")
            .and(not(resideInAPackage("org.springframework.data.."))))
        .as("domain은 Spring에 의존하지 않는다(JPA·Spring Data 제외)");

    static final ArchRule DOMAIN_DOES_NOT_PARSE_JSON = noClasses()
        .that().resideInAPackage(DOMAIN)
        .should().dependOnClassesThat().resideInAnyPackage("tools.jackson..", "com.fasterxml.jackson..")
        .as("domain은 Jackson에 의존하지 않는다(요청 JSON 해석은 web에서 끝낸다)");

    static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS = noClasses()
        .that().resideInAPackage(DOMAIN)
        .should().dependOnClassesThat().resideInAnyPackage("..web..", "..application..")
        .as("domain은 web·application에 의존하지 않는다");

    static final ArchRule CONTROLLERS_DO_NOT_USE_REPOSITORIES = noClasses()
        .that().resideInAPackage("com.moeun.backend..web..")
        .should().dependOnClassesThat(resideInAPackage(DOMAIN)
            .and(simpleNameEndingWith("Repository")))
        .as("web은 domain 리포지토리를 직접 쓰지 않는다(application 서비스를 거친다)");

    static final ArchRule NO_IMPL_SUFFIX = noClasses()
        .that().resideInAPackage("com.moeun.backend..")
        .should().haveSimpleNameEndingWith("Impl")
        .as("클래스 이름은 Impl로 끝나지 않는다(구현 방식을 드러내는 이름을 쓴다)");

    static final List<ArchRule> FROZEN_RULES = List.of(DOMAIN_DEPENDS_ONLY_ON_PERSISTENCE_SPRING,
        DOMAIN_DOES_NOT_PARSE_JSON, DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS, CONTROLLERS_DO_NOT_USE_REPOSITORIES,
        NO_IMPL_SUFFIX);

    static final Path STORED_RULES = Path.of("src/test/resources/archunit_store/stored.rules");

    static final JavaClasses PRODUCTION = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.moeun.backend");

    @Test
    void domainDependsOnlyOnPersistenceSpring() {
        FreezingArchRule.freeze(DOMAIN_DEPENDS_ONLY_ON_PERSISTENCE_SPRING).check(PRODUCTION);
    }

    @Test
    void domainDoesNotParseJson() {
        FreezingArchRule.freeze(DOMAIN_DOES_NOT_PARSE_JSON).check(PRODUCTION);
    }

    @Test
    void domainDoesNotDependOnOuterLayers() {
        FreezingArchRule.freeze(DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS).check(PRODUCTION);
    }

    @Test
    void controllersDoNotUseRepositories() {
        FreezingArchRule.freeze(CONTROLLERS_DO_NOT_USE_REPOSITORIES).check(PRODUCTION);
    }

    @Test
    void noImplSuffix() {
        FreezingArchRule.freeze(NO_IMPL_SUFFIX).check(PRODUCTION);
    }

    /**
     * 저장소에 없는 규칙은 allowStoreUpdate 때문에 그 시점의 위반째로 조용히 동결된다. 규칙 설명(저장소 키)을 고치거나
     * 규칙을 추가하면 여기서 실패하게 해서, 저장소 변경이 리뷰를 거치게 한다.
     */
    @Test
    void storeHoldsExactlyTheFrozenRules() throws IOException {
        Properties stored = new Properties();
        try (InputStream in = Files.newInputStream(STORED_RULES)) {
            stored.load(in);
        }
        assertThat(stored.stringPropertyNames())
            .containsExactlyInAnyOrderElementsOf(FROZEN_RULES.stream().map(ArchRule::getDescription).toList());
    }

    // 아래는 규칙이 새 위반을 실제로 잡는지 archfixture의 표본으로 확인한다

    @Test
    void domainSpringDependencyIsCaughtButSpringDataIsAllowed() {
        assertViolation(DOMAIN_DEPENDS_ONLY_ON_PERSISTENCE_SPRING, SpringDependentRule.class);
        DOMAIN_DEPENDS_ONLY_ON_PERSISTENCE_SPRING.check(importClasses(SpringDataSortRule.class));
    }

    @Test
    void domainJacksonDependencyIsCaught() {
        assertViolation(DOMAIN_DOES_NOT_PARSE_JSON, JsonParsingRule.class);
    }

    @Test
    void domainDependencyOnWebOrApplicationIsCaught() {
        assertViolation(DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS, LayerDependentRule.class);
    }

    @Test
    void controllerUsingRepositoryIsCaught() {
        assertViolation(CONTROLLERS_DO_NOT_USE_REPOSITORIES, SampleController.class, SampleRepository.class);
    }

    @Test
    void implSuffixIsCaught() {
        assertViolation(NO_IMPL_SUFFIX, SampleServiceImpl.class);
    }

    private static void assertViolation(ArchRule rule, Class<?>... classes) {
        assertThatThrownBy(() -> rule.check(importClasses(classes)))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining(classes[0].getSimpleName());
    }

    private static JavaClasses importClasses(Class<?>... classes) {
        return new ClassFileImporter().importClasses(classes);
    }
}
