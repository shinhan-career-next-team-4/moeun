package com.moeun.backend.chat.alternative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.application.AlternativeCandidatePolicy;

import tools.jackson.databind.JsonNode;

/**
 * 후보값 정책이 값을 낼 때의 응답(FR-ALT-03). 실제 정책은 미확정(Q-089·Q-103)이라 고정값을 내는 스텁 정책으로 검증한다.
 * 스텁의 숫자는 정책이 아니라 "정책이 준 값 하나만 바꿔 계산한다"를 확인하기 위한 입력이다.
 */
class AlternativeCandidatesTest extends AlternativeTestSupport {

    private static final long STUB_MONTHLY = 500_000L;
    private static final int STUB_RATIO = 70;

    @TestConfiguration
    static class StubPolicyConfig {

        @Bean
        @Primary
        AlternativeCandidatePolicy stubPolicy() {
            return new AlternativeCandidatePolicy() {
                @Override
                public Optional<Long> monthlySavingAmount(CalcInput current) {
                    return Optional.of(STUB_MONTHLY);
                }

                @Override
                public Optional<Integer> savingsRatioPct(CalcInput current) {
                    return Optional.of(STUB_RATIO);
                }
            };
        }
    }

    @Test
    @DisplayName("미달 계획이면 월 저축액·기한·비중 3가지가 각각 하나의 조건만 바꾼 결과로 온다(FR-ALT-01·03)")
    void threeAlternativesEachChangeOneCondition() throws Exception {
        savePlan(MISSING_MONTHLY);

        JsonNode response = read(requestAlternatives("{\"targetPeriodMonths\":48}").andExpect(status().isOk()));

        assertThat(response.get("hasAlternatives").asBoolean()).isTrue();
        JsonNode cards = response.get("alternatives");
        assertThat(cards.size()).isEqualTo(3);
        assertOnlyChanged(cards.get(0), "monthlySavingAmount", STUB_MONTHLY);
        assertOnlyChanged(cards.get(1), "targetPeriodMonths", 48);
        assertOnlyChanged(cards.get(2), "savingsRatioPct", STUB_RATIO);
    }

    @Test
    @DisplayName("대안의 changed 결과는 바꾼 조건으로 다시 계산한 값이고 current는 세 대안이 같다")
    void changedResultIsRecomputedAndCurrentIsShared() throws Exception {
        savePlan(MISSING_MONTHLY);

        JsonNode cards = read(requestAlternatives("{\"targetPeriodMonths\":48}").andExpect(status().isOk()))
                .get("alternatives");

        long currentAsset = afterTaxAsset(cards.get(0).get("current"));
        assertThat(afterTaxAsset(cards.get(1).get("current"))).isEqualTo(currentAsset);
        assertThat(afterTaxAsset(cards.get(2).get("current"))).isEqualTo(currentAsset);
        assertThat(afterTaxAsset(cards.get(0).get("changed"))).isGreaterThan(currentAsset);
        assertThat(afterTaxAsset(cards.get(1).get("changed"))).isGreaterThan(currentAsset);
        assertThat(cards.get(0).get("diff").get("byScenario").size()).isPositive();
    }

    @Test
    @DisplayName("정책이 값을 내지 않은 후보는 그 카드만 빈 카드다")
    void candidateWithoutValueStaysEmpty() throws Exception {
        savePlan(MISSING_MONTHLY);

        JsonNode cards = read(requestAlternatives("{}").andExpect(status().isOk())).get("alternatives");

        assertThat(cards.get(0).get("changed").isNull()).isFalse();
        assertThat(cards.get(1).get("changed").isNull()).isTrue();
        assertThat(cards.get(2).get("changed").isNull()).isFalse();
    }

    private static long afterTaxAsset(JsonNode result) {
        JsonNode scenarios = result.get("scenarios");
        for (JsonNode scenario : scenarios) {
            if ("AVG".equals(scenario.get("scenario").asString())) {
                return scenario.get("afterTaxAsset").asLong();
            }
        }
        throw new AssertionError("AVG scenario missing");
    }

    /** 바꾼 필드 하나만 값이 달라지고 나머지 세 조건은 current와 같다. */
    private static void assertOnlyChanged(JsonNode card, String key, long value) {
        assertThat(card.get("key").asString()).isEqualTo(key);
        assertThat(card.get("changes").size()).isEqualTo(1);
        assertThat(card.get("changes").get(key).asLong()).isEqualTo(value);
        JsonNode current = card.get("conditions").get("current");
        JsonNode changed = card.get("conditions").get("changed");
        for (String field : new String[] {"goalAmount", "monthlySavingAmount", "savingsRatioPct",
            "savingsRateInputPct", "targetPeriodMonths"}) {
            if (field.equals(key)) {
                assertThat(changed.get(field).asLong()).isEqualTo(value);
                assertThat(changed.get(field)).isNotEqualTo(current.get(field));
            } else {
                assertThat(changed.get(field)).as(field).isEqualTo(current.get(field));
            }
        }
    }
}
