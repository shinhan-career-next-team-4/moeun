package com.moeun.backend.config.copy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 문구표 경로 설정 테스트(Issue #219). 서블릿 웹 컨텍스트에서는 Path 파라미터가 서블릿 리소스로 해석돼
 * 상대 경로(../copy/copy.ko.yml)가 Tomcat에서 거부됐다.
 */
class CopyConfigTest {

    @Nested
    @SpringBootTest(classes = {CopyConfig.class, TomcatServletWebServerAutoConfiguration.class},
            webEnvironment = WebEnvironment.RANDOM_PORT)
    @DisplayName("서블릿 웹 컨텍스트에서")
    class InServletContext {

        @Autowired
        private CopyBook copyBook;

        @Test
        @DisplayName("기본 상대 경로로 문구표를 읽는다")
        void loadsWithDefaultRelativePath() {
            assertThat(copyBook).isNotNull();
        }
    }

    @Test
    @DisplayName("(예외) 없는 파일이면 문구표를 읽지 못했다는 오류로 기동이 실패한다")
    void failsWhenFileMissing() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(CopyConfig.class)
                .web(WebApplicationType.NONE)
                .properties("moeun.copy.path=../copy/없는-문구표.yml");

        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext ignored = app.run()) {
                // 기동 실패여야 한다
            }
        }).hasStackTraceContaining("문구표를 읽지 못했어요");
    }
}
