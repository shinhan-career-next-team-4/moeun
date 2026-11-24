package com.moeun.backend.plan.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.moeun.backend.plan.ChangePath;

/** 적용 가능 경로 규칙(계약 C12b-13). 입력 수정은 저장 경로가 따로 있다. */
class PlanSourceTest {

    @Test
    void applyPathsMapToSameSource() {
        assertThat(PlanSource.appliedBy(ChangePath.ALTERNATIVE)).isEqualTo(PlanSource.ALTERNATIVE);
        assertThat(PlanSource.appliedBy(ChangePath.COMPARE)).isEqualTo(PlanSource.COMPARE);
        assertThat(PlanSource.appliedBy(ChangePath.CHAT)).isEqualTo(PlanSource.CHAT);
    }

    @Test
    void inputEditCannotBeApplied() {
        assertThatThrownBy(() -> PlanSource.appliedBy(ChangePath.INPUT_EDIT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
