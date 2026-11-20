package com.moeun.backend.chat.alternative;

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
 * 대안 값 산출 {@code POST /api/alternatives}(#66, FR-ALT-01·03). 월 저축액·비중 후보값의 정책은 미확정(Q-089·Q-103)이라
 * 이 클래스는 정책 기본 구현(후보 없음)으로 돌고, 후보값이 있을 때의 응답은 {@link AlternativeCandidatesTest}가 본다.
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
    @DisplayName("미달 계획에 기간을 주면 '기한을 늘리면'만 그 기간 하나만 바꾼 결과로 오고, 정책이 없는 월 저축액·비중 카드는 비어 있다")
    void missingPlanWithoutCandidatePolicyFillsOnlyDeadlineCard() throws Exception {
        savePlan(MISSING_MONTHLY);

        JsonNode response = read(requestAlternatives("{\"targetPeriodMonths\":48}").andExpect(status().isOk()));

        assertThat(response.get("hasAlternatives").asBoolean()).isTrue();
        JsonNode cards = response.get("alternatives");
        assertThat(cards.size()).isEqualTo(3);
        assertThat(cards.get(0).get("key").asString()).isEqualTo("monthlySavingAmount");
        assertThat(cards.get(0).get("changed").isNull()).isTrue();
        assertThat(cards.get(2).get("key").asString()).isEqualTo("savingsRatioPct");
        assertThat(cards.get(2).get("changed").isNull()).isTrue();
        JsonNode deadline = cards.get(1);
        assertThat(deadline.get("key").asString()).isEqualTo("targetPeriodMonths");
        assertThat(deadline.get("changes").get("targetPeriodMonths").asInt()).isEqualTo(48);
        assertThat(deadline.get("conditions").get("changed").get("targetPeriodMonths").asInt()).isEqualTo(48);
    }

    @Test
    @DisplayName("기간을 주지 않으면 '기한을 늘리면'은 입력 전의 빈 카드다(D-039)")
    void deadlineCardIsEmptyBeforePeriodInput() throws Exception {
        savePlan(MISSING_MONTHLY);

        JsonNode response = read(requestAlternatives("{}").andExpect(status().isOk()));

        JsonNode deadline = response.get("alternatives").get(1);
        assertThat(deadline.get("key").asString()).isEqualTo("targetPeriodMonths");
        assertThat(deadline.get("changed").isNull()).isTrue();
        assertThat(deadline.get("changes").size()).isZero();
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
