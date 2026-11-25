package com.moeun.backend.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** 원인 추적용 로그 파일은 날짜별로 나누고 기본 7일만 보관한다(#316). 줄마다 오프셋이 붙은 시각을 남긴다. */
class LogRetentionConfigTest {

    private static PropertySource<?> applicationYaml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        return sources.getFirst();
    }

    @Test
    void writesDailyFileKeptForRetentionDays() throws IOException {
        PropertySource<?> yaml = applicationYaml();

        assertThat(yaml.getProperty("logging.file.name")).isEqualTo("${LOG_DIR:logs}/backend.log");
        assertThat(String.valueOf(yaml.getProperty("logging.logback.rollingpolicy.file-name-pattern")))
                .contains("%d{yyyy-MM-dd}");
        assertThat(yaml.getProperty("logging.logback.rollingpolicy.max-history")).isEqualTo("${LOG_RETENTION_DAYS:7}");
    }

    @Test
    void fileLinesStartWithOffsetTime() throws IOException {
        assertThat(String.valueOf(applicationYaml().getProperty("logging.pattern.file")))
                .startsWith("%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}");
    }
}
