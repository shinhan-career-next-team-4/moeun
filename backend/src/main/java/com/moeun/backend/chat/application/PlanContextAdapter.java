package com.moeun.backend.chat.application;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.moeun.backend.calc.PlanContextProvider;
import com.moeun.backend.calc.PlanContextView;
import com.moeun.backend.calc.Scenario;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@link PlanContextPort}를 calc의 공개 API {@link PlanContextProvider}에 잇는 어댑터(사람 결정 U-1, 계약 C1a-10).
 * 빈 값은 그대로 전달하고, 계산·기준 데이터 오류는 잡지 않는다. 리스너가 예외를 받으면 ai-server를 부르지 않고 FAILED로 기록한다.
 */
@Component
class PlanContextAdapter implements PlanContextPort {

    private final PlanContextProvider provider;
    private final JsonMapper json;

    PlanContextAdapter(PlanContextProvider provider, JsonMapper json) {
        this.provider = provider;
        this.json = json;
    }

    @Override
    public Optional<PlanContextSnapshot> findForCurrentPlan(long memberId) {
        return provider.findForCurrentPlan(memberId).map(this::toSnapshot);
    }

    @Override
    public Optional<PlanContextSnapshot> findForCalcRun(long memberId, long calcRunId, Scenario baselineScenario) {
        return provider.findForCalcRun(memberId, calcRunId, baselineScenario).map(this::toSnapshot);
    }

    private PlanContextSnapshot toSnapshot(PlanContextView view) {
        return new PlanContextSnapshot(view.result().baselineScenario(), json.valueToTree(view));
    }
}
