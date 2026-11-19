package com.moeun.backend.calc;

import java.util.Map;

/**
 * 대화 비교 기록(calc_run(CHAT))을 다시 계산한 결과(프론트-Spring 대화 API 계약 H-8b, C12b-6).
 * <p>display는 calc 안의 공유 빌더로 만들어 루트 공개 타입인 맵으로 내보낸다. chat이 calc 하위 패키지 타입을 쓰면
 * 모듈 경계를 어긴다.
 *
 * @param basePlanVersionId 비교의 기준이었던 계획 버전(그 기록의 plan_version_id)
 * @param comparison current·changed·conditions·diff. {@code /internal/calc} 때와 같은 값이다
 * @param display {@code current.}·{@code changed.}·{@code conditions.current.}·{@code conditions.changed.}·
 *     {@code diff.} 키
 */
public record ChatComparisonView(long basePlanVersionId, CalcComparison comparison, Map<String, String> display) {

    public ChatComparisonView {
        display = Map.copyOf(display);
    }
}
