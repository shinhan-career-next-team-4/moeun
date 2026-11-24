package com.moeun.backend.plan.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.moeun.backend.plan.NothingToUndoException;
import com.moeun.backend.plan.PlanChangedException;

/** 현재 계획 포인터 규칙. 스프링 없이 돈다. revision 증가는 JPA @Version이 하므로 PlanServiceTest가 본다. */
class PlanTest {

    @Test
    void newPlanPointsToFirstVersionWithAvgBaselineAndNothingToUndo() {
        Plan plan = Plan.start(7L, 10L);

        assertThat(plan.getMemberId()).isEqualTo(7L);
        assertThat(plan.getCurrentVersionId()).isEqualTo(10L);
        assertThat(plan.getPreviousVersionId()).isNull();
        assertThat(plan.getBaselineScenario()).isEqualTo(BaselineScenario.AVG);
        assertThat(plan.getRevision()).isEqualTo(1L);
        assertThat(plan.canUndo()).isFalse();
    }

    @Test
    void pointToRemembersCurrentAsPrevious() {
        Plan plan = Plan.start(7L, 10L);

        plan.pointTo(11L);

        assertThat(plan.getCurrentVersionId()).isEqualTo(11L);
        assertThat(plan.getPreviousVersionId()).isEqualTo(10L);
        assertThat(plan.canUndo()).isTrue();
    }

    @Test
    void undoToPointsToCopyAndForgetsPrevious() {
        Plan plan = Plan.start(7L, 10L);
        plan.pointTo(11L);

        plan.undoTo(12L);

        assertThat(plan.getCurrentVersionId()).isEqualTo(12L);
        assertThat(plan.getPreviousVersionId()).isNull();
        assertThat(plan.canUndo()).isFalse();
    }

    @Test
    void undoWithoutPreviousIsNothingToUndo() {
        Plan plan = Plan.start(7L, 10L);

        assertThatThrownBy(() -> plan.undoTo(12L)).isInstanceOf(NothingToUndoException.class);
        assertThat(plan.getCurrentVersionId()).isEqualTo(10L);
    }

    @Test
    void requireRevisionRejectsOtherOrMissingRevision() {
        Plan plan = Plan.start(7L, 10L);

        plan.requireRevision(1L);
        assertThatThrownBy(() -> plan.requireRevision(2L)).isInstanceOf(PlanChangedException.class);
        assertThatThrownBy(() -> plan.requireRevision(null)).isInstanceOf(PlanChangedException.class);
    }
}
