package com.moeun.backend.calc.application;

import java.util.List;
import java.util.Map;

import com.moeun.backend.calc.CalcComparison;

/**
 * 대안 산출 결과(FR-ALT-01·03). calc 안(대안 REST)에서만 쓴다(#309, #311).
 *
 * @param needed 대안을 보여줄지. 기준 시나리오로 목표를 달성했거나 이미 넘은 계획이면 false(대안 없음)
 * @param alternatives needed일 때 {@link AlternativeKey} 선언 순서의 3개. needed가 아니면 빈 목록
 */
public record AlternativeView(boolean needed, List<Alternative> alternatives) {

    public AlternativeView {
        alternatives = List.copyOf(alternatives);
    }

    public static AlternativeView notNeeded() {
        return new AlternativeView(false, List.of());
    }

    /**
     * 대안 카드 하나. 바꿀 값이 아직 없으면 빈 카드다(기간 입력 전이거나 후보값 정책이 값을 내지 않음).
     *
     * @param key 바꾼 조건
     * @param changedValue 그 조건의 바꾼 값(월 저축액은 원, 기간은 개월, 비중은 %). 빈 카드면 null
     * @param comparison 현재 계획과 그 조건 하나만 바꾼 결과의 비교. 빈 카드면 null
     * @param display 비교의 표시 문자열 맵. 빈 카드면 빈 맵
     * @param unreachable 허용 범위 안에서 목표를 달성하는 값을 찾지 못했다(달성이 어려움, #343). 이때 값·비교는 없다
     */
    public record Alternative(AlternativeKey key, Number changedValue, CalcComparison comparison,
            Map<String, String> display, boolean unreachable) {

        public Alternative {
            display = Map.copyOf(display);
        }

        public Alternative(AlternativeKey key, Number changedValue, CalcComparison comparison,
                Map<String, String> display) {
            this(key, changedValue, comparison, display, false);
        }

        public static Alternative empty(AlternativeKey key) {
            return new Alternative(key, null, null, Map.of(), false);
        }

        /** 허용 범위 안에서 목표를 달성하는 값이 없는 카드(#343). */
        public static Alternative unreachable(AlternativeKey key) {
            return unreachable(key, Map.of());
        }

        /** 달성하는 값이 없는 카드. display에 안내에 쓸 값(예: 시도한 상한 금액)을 싣는다. */
        public static Alternative unreachable(AlternativeKey key, Map<String, String> display) {
            return new Alternative(key, null, null, display, true);
        }
    }
}
