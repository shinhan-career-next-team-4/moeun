package com.moeun.backend.calc.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.PlanView;
import com.moeun.backend.refdata.EtfProduct;

/** 계획을 어느 ETF 종목으로 계산할지 정하는 한 곳(#150, #154). */
class EtfProductSelectorTest {

    private static PlanView planWith(String etfProductCode) {
        return new PlanView(1L, GoalType.HOUSE, null, 20_000_000L, 36, 5_000_000L, 300_000L, null, null,
                etfProductCode, "2026-11", "AVG", 1L, false, Instant.parse("2026-11-20T00:00:00Z"));
    }

    @Test
    @DisplayName("계획에 저장된 종목 코드로 계산한다")
    void returnsEtfProductOfPlan() {
        assertThat(EtfProductSelector.productCodeOf(planWith("069500"))).isEqualTo("069500");
        assertThat(EtfProductSelector.productCodeOf(planWith("133690"))).isEqualTo("133690");
    }

    @Test
    @DisplayName("plan이 새 계획에 넣는 기본 종목은 refdata의 대표 상품과 같다")
    void usesRepresentativeProductAsPlanDefault() {
        assertThat(PlanView.DEFAULT_ETF_PRODUCT_CODE).isEqualTo(EtfProduct.REPRESENTATIVE_CODE);
        assertThat(EtfProductSelector.productCodeOf(planWith(PlanView.DEFAULT_ETF_PRODUCT_CODE)))
                .isEqualTo(EtfProduct.REPRESENTATIVE_CODE);
    }
}
