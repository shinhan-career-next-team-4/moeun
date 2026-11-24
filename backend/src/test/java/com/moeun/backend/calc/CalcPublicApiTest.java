package com.moeun.backend.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

import com.moeun.backend.BackendApplication;
import com.tngtech.archunit.core.domain.JavaClass;

/**
 * calc 공개 API는 역할별 named interface로만 연다(#311). chat은 그 이름으로만 calc에 의존하고, calc 루트 패키지에는
 * named interface에 속한 타입만 남는다. 스프링 컨텍스트·Docker 없이 돈다.
 */
class CalcPublicApiTest {

    static final Set<String> CALC_INTERFACES = Set.of("plan-context", "chat-comparison", "calc-run-etf");

    static final ApplicationModules MODULES = ApplicationModules.of(BackendApplication.class);

    @Test
    void calcDeclaresRoleNamedInterfaces() {
        Set<String> names = calc().getNamedInterfaces().stream()
                .filter(named -> !named.isUnnamed())
                .map(NamedInterface::getName)
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrderElementsOf(CALC_INTERFACES);
    }

    @Test
    void chatDependsOnCalcOnlyThroughNamedInterfaces() throws ClassNotFoundException {
        List<String> calcDependencies = Arrays.stream(Class.forName("com.moeun.backend.chat.package-info")
                        .getAnnotation(ApplicationModule.class).allowedDependencies())
                .filter(dependency -> dependency.startsWith("calc"))
                .toList();

        assertThat(calcDependencies).containsExactlyInAnyOrderElementsOf(
                CALC_INTERFACES.stream().map(name -> "calc :: " + name).toList());
    }

    /** 루트의 타입은 모두 다른 모듈에 여는 공개 API다. calc 안에서만 쓰는 타입은 하위 패키지에 둔다. */
    @Test
    void calcRootHoldsOnlyNamedInterfaceTypes() {
        List<NamedInterface> named = calc().getNamedInterfaces().stream()
                .filter(namedInterface -> !namedInterface.isUnnamed())
                .toList();
        List<String> rootTypes = calc().getBasePackage().stream()
                .filter(type -> type.getPackageName().equals("com.moeun.backend.calc"))
                .filter(JavaClass::isTopLevelClass)
                .map(JavaClass::getSimpleName)
                .filter(name -> !name.equals("package-info"))
                .toList();
        List<String> outside = calc().getBasePackage().stream()
                .filter(type -> type.getPackageName().equals("com.moeun.backend.calc"))
                .filter(JavaClass::isTopLevelClass)
                .filter(type -> !type.getSimpleName().equals("package-info"))
                .filter(type -> named.stream().noneMatch(namedInterface -> namedInterface.contains(type)))
                .map(JavaClass::getSimpleName)
                .toList();

        assertThat(rootTypes).isNotEmpty();
        assertThat(outside).as("named interface에 속하지 않는 calc 루트 타입").isEmpty();
    }

    @Test
    void calcInternalTypesLeftRootPackage() {
        assertThat(List.of("AlternativeKey", "AlternativeQuery", "AlternativeView", "CalcRejectedException",
                "ConditionComparisonQuery", "CurrentPlanResult", "CurrentPlanResultService"))
                .noneMatch(name -> exists("com.moeun.backend.calc." + name));
    }

    private static org.springframework.modulith.core.ApplicationModule calc() {
        return MODULES.getModuleByName("calc").orElseThrow();
    }

    private static boolean exists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
