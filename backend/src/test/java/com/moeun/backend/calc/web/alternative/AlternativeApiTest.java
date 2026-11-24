package com.moeun.backend.calc.web.alternative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

/**
 * 대안 값 산출 {@code POST /api/alternatives}(#66, FR-ALT-01·03). 후보값은 운영 정책(월 저축액 +10%, 비중 10%p)으로 돌고,
 * 정책이 값을 내지 않는 경우의 응답은 {@link AlternativeCandidatesTest}가 스텁 정책으로 본다.
 */
class AlternativeApiTest extends AlternativeTestSupport {

    @Test
    @DisplayName("달성하는 계획이면 대안 없음 응답이다(FR-ALT-01)")
    void achievingPlanHasNoAlternatives() throws Exception {
        savePlan(ACHIEVING_MONTHLY);

        JsonNode response = read(requestAlternatives("{\"targetPeriodMonths\":48}").andExpect(status().isOk()));

        assertThat(response.get("hasAlternatives").asBoolean()).isFalse();
        assertThat(response.get("alternatives").size()).isZero();
    }

    @Test
    @DisplayName("이미 목표를 넘은 계획이면 대안 없음 응답이다")
    void exceededPlanHasNoAlternatives() throws Exception {
        savePlan(MISSING_MONTHLY, GOAL + 1_000_000L);

        JsonNode response = read(requestAlternatives("{}").andExpect(status().isOk()));

        assertThat(response.get("hasAlternatives").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("미달 계획이면 세 대안은 각각 조건 하나만 바꿔 기준 시나리오로 목표를 달성하는 값을 추천한다(#343)")
    void alternativesRecommendGoalAchievingValues() throws Exception {
        savePlan(MISSING_MONTHLY);

        JsonNode cards = read(requestAlternatives("{}").andExpect(status().isOk())).get("alternatives");

        int currentRatio = cards.get(0).get("conditions").get("current").get("savingsRatioPct").asInt();
        assertThat(cards.size()).isEqualTo(3);
        assertThat(cards.get(0).get("key").asString()).isEqualTo("monthlySavingAmount");
        assertThat(cards.get(1).get("key").asString()).isEqualTo("targetPeriodMonths");
        assertThat(cards.get(2).get("key").asString()).isEqualTo("savingsRatioPct");
        for (JsonNode card : cards) {
            if (card.get("unreachable").asBoolean()) {
                // 이 계획에서는 비중을 바꿔도 달성하지 못할 수 있다(ETF 100%로도 부족). 그러면 값 없는 카드다
                assertThat(card.get("key").asString()).isEqualTo("savingsRatioPct");
                assertThat(card.get("changed").isNull()).isTrue();
                continue;
            }
            assertThat(avgAchieved(card.get("changed"))).as(card.get("key").asString()).isTrue();
        }
        assertThat(cards.get(0).get("unreachable").asBoolean()).isFalse();
        assertThat(cards.get(1).get("unreachable").asBoolean()).isFalse();
        long monthly = cards.get(0).get("changes").get("monthlySavingAmount").asLong();
        assertThat(monthly).isGreaterThan(MISSING_MONTHLY).isEqualTo(monthly / 10_000L * 10_000L);
        assertThat(cards.get(1).get("changes").get("targetPeriodMonths").asInt()).isGreaterThan(PERIOD);
        if (!cards.get(2).get("unreachable").asBoolean()) {
            assertThat(cards.get(2).get("changes").get("savingsRatioPct").asInt()).isLessThan(currentRatio);
        }
    }

    @Test
    @DisplayName("추천은 달성하는 가장 작은 값이다: 월 저축액은 만원 덜 올리면, 기간은 한 달 덜 늘리면 달성하지 못한다")
    void recommendationIsTheSmallestAchievingValue() throws Exception {
        savePlan(MISSING_MONTHLY);
        JsonNode cards = read(requestAlternatives("{}").andExpect(status().isOk())).get("alternatives");
        long monthly = cards.get(0).get("changes").get("monthlySavingAmount").asLong();
        int months = cards.get(1).get("changes").get("targetPeriodMonths").asInt();

        resetPlans();
        savePlan(monthly - 10_000L);
        assertThat(read(requestAlternatives("{}").andExpect(status().isOk())).get("hasAlternatives").asBoolean())
                .isTrue();
        resetPlans();
        savePlan(monthly);
        assertThat(read(requestAlternatives("{}").andExpect(status().isOk())).get("hasAlternatives").asBoolean())
                .isFalse();
        // 기간도 마찬가지: 추천 기간으로 늘리면 달성하고 한 달 덜 늘리면 달성하지 못한다
        resetPlans();
        planSaveService.save(memberId, validPlanWithPeriod(months), null);
        awaitEventsCompleted();
        assertThat(read(requestAlternatives("{}").andExpect(status().isOk())).get("hasAlternatives").asBoolean())
                .isFalse();
        resetPlans();
        planSaveService.save(memberId, validPlanWithPeriod(months - 1), null);
        awaitEventsCompleted();
        assertThat(read(requestAlternatives("{}").andExpect(status().isOk())).get("hasAlternatives").asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName("기간을 정해 보내면 '기한을 늘리면'은 그 값으로 계산한다")
    void givenPeriodIsUsedAsIs() throws Exception {
        savePlan(MISSING_MONTHLY);

        JsonNode cards = read(requestAlternatives("{\"targetPeriodMonths\":48}").andExpect(status().isOk()))
                .get("alternatives");

        assertThat(cards.get(1).get("changes").get("targetPeriodMonths").asInt()).isEqualTo(48);
    }

    @Test
    @DisplayName("허용 범위 안에서 달성하는 값이 없으면 달성이 어려운 카드(unreachable)다")
    void unreachableWhenNothingAchievesGoal() throws Exception {
        savePlan(10_000_000_000L, MISSING_MONTHLY, HELD);

        JsonNode cards = read(requestAlternatives("{}").andExpect(status().isOk())).get("alternatives");

        for (JsonNode card : cards) {
            assertThat(card.get("unreachable").asBoolean()).as(card.get("key").asString()).isTrue();
            assertThat(card.get("changed").isNull()).isTrue();
            assertThat(card.get("changes").size()).isZero();
        }
    }

    private static boolean avgAchieved(JsonNode result) {
        for (JsonNode scenario : result.get("scenarios")) {
            if ("AVG".equals(scenario.get("scenario").asString())) {
                return scenario.get("isAchieved").asBoolean();
            }
        }
        throw new AssertionError("AVG scenario missing");
    }

    @ParameterizedTest(name = "기한 {0}개월은 허용 범위 밖이라 422")
    @ValueSource(ints = {0, 361})
    @DisplayName("기한을 늘리는 입력이 허용 범위 밖이면 422 VALIDATION_FAILED")
    void periodOutOfRangeIsRejected(int months) throws Exception {
        savePlan(MISSING_MONTHLY);

        JsonNode response = read(requestAlternatives("{\"targetPeriodMonths\":" + months + "}")
                .andExpect(status().isUnprocessableEntity()));

        assertThat(response.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.get("errors").get(0).get("field").asString()).isEqualTo("targetPeriodMonths");
        assertThat(response.get("errors").get(0).get("reason").asString()).isEqualTo("OUT_OF_RANGE");
    }

    @Test
    @DisplayName("달성하는 계획이어도 허용 범위 밖 기한은 422다(검증이 먼저)")
    void periodOutOfRangeIsRejectedBeforeJudgement() throws Exception {
        savePlan(ACHIEVING_MONTHLY);

        requestAlternatives("{\"targetPeriodMonths\":361}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("계획이 없으면 409 NO_PLAN")
    void noPlanIsConflict() throws Exception {
        JsonNode response = read(requestAlternatives("{}").andExpect(status().isConflict()));

        assertThat(response.get("code").asString()).isEqualTo("NO_PLAN");
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(post("/api/alternatives").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
