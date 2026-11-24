package com.moeun.backend.calc.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GoalSeekerTest {

    @Test
    @DisplayName("목표를 달성하는 가장 작은 값을 찾는다")
    void findsSmallestAchievingValue() {
        assertThat(GoalSeeker.smallestAchieving(1, 100, value -> value >= 37).getAsLong()).isEqualTo(37);
        assertThat(GoalSeeker.smallestAchieving(1, 100, value -> value >= 1).getAsLong()).isEqualTo(1);
        assertThat(GoalSeeker.smallestAchieving(1, 100, value -> value >= 100).getAsLong()).isEqualTo(100);
    }

    @Test
    @DisplayName("상한에서도 달성하지 못하거나 범위가 비면 값이 없다")
    void emptyWhenUnreachable() {
        assertThat(GoalSeeker.smallestAchieving(1, 100, value -> false)).isEmpty();
        assertThat(GoalSeeker.smallestAchieving(5, 4, value -> true)).isEmpty();
    }

    @Test
    @DisplayName("후보를 순서대로 시험해 처음 달성하는 값을 준다")
    void firstAchievingFollowsCandidateOrder() {
        long[] candidates = {50, 40, 30, 20};

        assertThat(GoalSeeker.firstAchieving(candidates, value -> value <= 30).getAsLong()).isEqualTo(30);
        assertThat(GoalSeeker.firstAchieving(candidates, value -> value < 0)).isEmpty();
        assertThat(GoalSeeker.firstAchieving(new long[0], value -> true)).isEmpty();
    }
}
