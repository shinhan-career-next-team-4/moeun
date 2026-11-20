package com.moeun.backend.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.calc.CalcResultView;
import com.moeun.backend.calc.PlanContextProvider;
import com.moeun.backend.calc.PlanContextView;
import com.moeun.backend.calc.Scenario;

import tools.jackson.databind.json.JsonMapper;

/** #128: 어댑터가 calc.PlanContextProvider의 결과·빈 값·예외를 그대로 chat 포트 형식으로 잇는다. */
class PlanContextAdapterTest {

    private final PlanContextProvider provider = mock(PlanContextProvider.class);
    private final PlanContextAdapter adapter = new PlanContextAdapter(provider, JsonMapper.builder().build());

    @Test
    @DisplayName("현재 계획의 컨텍스트는 본문 JSON과 그 기준 시나리오로 바뀐다")
    void currentPlanIsConverted() {
        PlanContextView view = view(7L, Scenario.BAD);
        given(provider.findForCurrentPlan(1L)).willReturn(Optional.of(view));

        PlanContextSnapshot snapshot = adapter.findForCurrentPlan(1L).orElseThrow();

        assertThat(snapshot.baselineScenario()).isEqualTo(Scenario.BAD);
        assertThat(snapshot.body().get("calcRunId").asLong()).isEqualTo(7L);
        assertThat(snapshot.body().propertyNames()).containsExactlyInAnyOrder("calcRunId", "conditions", "result",
                "display");
    }

    @Test
    @DisplayName("EXPLAIN은 저장된 기준 시나리오를 Provider에 넘기고 그 결과를 변환한다")
    void calcRunPassesStoredBaseline() {
        given(provider.findForCalcRun(1L, 9L, Scenario.GOOD)).willReturn(Optional.of(view(9L, Scenario.GOOD)));

        PlanContextSnapshot snapshot = adapter.findForCalcRun(1L, 9L, Scenario.GOOD).orElseThrow();

        assertThat(snapshot.baselineScenario()).isEqualTo(Scenario.GOOD);
        assertThat(snapshot.body().get("calcRunId").asLong()).isEqualTo(9L);
    }

    @Test
    @DisplayName("(예외) 계획·기록이 없으면 빈 값 그대로 돌려준다")
    void emptyStaysEmpty() {
        given(provider.findForCurrentPlan(1L)).willReturn(Optional.empty());
        given(provider.findForCalcRun(1L, 9L, Scenario.AVG)).willReturn(Optional.empty());

        assertThat(adapter.findForCurrentPlan(1L)).isEmpty();
        assertThat(adapter.findForCalcRun(1L, 9L, Scenario.AVG)).isEmpty();
    }

    @Test
    @DisplayName("(예외) 엔진 오류는 삼키지 않고 올려보낸다(리스너가 FAILED로 기록)")
    void engineErrorPropagates() {
        given(provider.findForCurrentPlan(1L)).willThrow(new IllegalStateException("계산 실패"));
        given(provider.findForCalcRun(1L, 9L, Scenario.AVG)).willThrow(new IllegalStateException("계산 실패"));

        assertThatThrownBy(() -> adapter.findForCurrentPlan(1L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.findForCalcRun(1L, 9L, Scenario.AVG))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PlanContextView view(long calcRunId, Scenario baseline) {
        CalcResultView result = new CalcResultView(null, 0L, null, null, baseline, List.of(), null, null, null);
        return new PlanContextView(calcRunId, null, result, Map.of("goalAmount", "2,000만원"));
    }
}
