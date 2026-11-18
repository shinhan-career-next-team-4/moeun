package com.moeun.backend.plan.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PlanRules.class)
class PlanConfig {
}
