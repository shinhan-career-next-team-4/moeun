package com.moeun.backend.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 앱 전역 시계. 날짜 기준은 한국 시간이다(계산 시작 월 등). 테스트에서 고정 시계로 바꿀 수 있게 빈으로 둔다. */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
