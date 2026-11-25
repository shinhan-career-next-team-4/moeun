package com.moeun.backend.calc.application;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.moeun.backend.plan.PlanChanged;

/**
 * 계획이 저장·적용·되돌리기로 바뀌면 새 현재 버전의 calc_run(PLAN)을 기록한다(modulith.md 이벤트 표).
 * <p>커밋 뒤 새 트랜잭션에서 돈다({@link ApplicationModuleListener}). 같은 트랜잭션의 리스너면 커밋 전에 돌아
 * 기록 잠금이 의미 없어진다. 이벤트는 재기동 때 다시 처리될 수 있지만 기록은 "없으면 기록"이라 결과가 같다.
 * 이벤트의 버전이 아니라 지금의 현재 계획을 기록한다. 그 사이 계획이 또 바뀌었으면 다음 이벤트가 그 버전을 맡는다.
 */
@Component
class PlanChangedListener {

    private final CurrentPlanResultService resultService;

    PlanChangedListener(CurrentPlanResultService resultService) {
        this.resultService = resultService;
    }

    @ApplicationModuleListener
    void on(PlanChanged event) {
        resultService.recordForCurrentPlan(event.memberId());
    }
}
