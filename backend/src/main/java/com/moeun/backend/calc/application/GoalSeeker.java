package com.moeun.backend.calc.application;

import java.util.OptionalLong;
import java.util.function.LongPredicate;

/**
 * 목표를 달성하는 값을 찾는 탐색(대안 추천, #343). 판정({@code achieves})은 부르는 쪽이 계산 엔진으로 만들고,
 * 이 클래스는 어느 값을 시험할지만 정한다. 값이 커질수록 달성하기 쉬워지는 조건(월 저축액·기간)에 쓴다.
 */
final class GoalSeeker {

    private GoalSeeker() {
    }

    /**
     * {@code lo}~{@code hi} 사이에서 목표를 달성하는 가장 작은 값. 값이 클수록 달성하기 쉽다고 보고 이분 탐색한다.
     * 상한({@code hi})에서도 달성하지 못하거나 범위가 비면 빈 값이다.
     */
    static OptionalLong smallestAchieving(long lo, long hi, LongPredicate achieves) {
        if (lo > hi || !achieves.test(hi)) {
            return OptionalLong.empty();
        }
        long low = lo;
        long high = hi;
        while (low < high) {
            long mid = low + (high - low) / 2;
            if (achieves.test(mid)) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return OptionalLong.of(low);
    }

    /**
     * 후보를 순서대로 시험해 처음으로 달성하는 값. 현재 값에서 가까운 후보부터 주면 변화가 가장 작은 값을 얻는다.
     */
    static OptionalLong firstAchieving(long[] candidates, LongPredicate achieves) {
        for (long candidate : candidates) {
            if (achieves.test(candidate)) {
                return OptionalLong.of(candidate);
            }
        }
        return OptionalLong.empty();
    }
}
