package com.moeun.backend.chat.application;

import com.moeun.backend.calc.Scenario;

import tools.jackson.databind.JsonNode;

/** ai-server에 보낼 planContext 본문과 그 기준 시나리오. */
public record PlanContextSnapshot(Scenario baselineScenario, JsonNode body) {
}
