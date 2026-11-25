package com.moeun.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** 모듈 경계 검증. 스프링 컨텍스트·Docker 없이 돈다. 패키지나 의존을 바꾸면 먼저 실행한다. */
class ModularityTests {

    ApplicationModules modules = ApplicationModules.of(BackendApplication.class);

    @Test
    void verifyModules() {
        modules.verify();
    }

    /** 대안·조건 비교 REST는 calc 공개 API만 부르므로 calc에 둔다(#309). */
    @Test
    void alternativeAndCompareControllersBelongToCalc() {
        assertThat(modules.getModuleByType("AlternativeController")).map(ModularityTests::name).contains("calc");
        assertThat(modules.getModuleByType("ConditionCompareController")).map(ModularityTests::name).contains("calc");
    }

    private static String name(ApplicationModule module) {
        return module.getIdentifier().toString();
    }

    @Test
    void writeDocumentation() {
        new Documenter(modules).writeDocumentation();   // build/spring-modulith-docs
    }
}
