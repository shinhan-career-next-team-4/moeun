package com.moeun.backend;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** 모듈 경계 검증. 스프링 컨텍스트·Docker 없이 돈다. 패키지나 의존을 바꾸면 먼저 실행한다. */
class ModularityTests {

    ApplicationModules modules = ApplicationModules.of(BackendApplication.class);

    @Test
    void verifyModules() {
        modules.verify();
    }

    @Test
    void writeDocumentation() {
        new Documenter(modules).writeDocumentation();   // build/spring-modulith-docs
    }
}
