package com.moeun.backend.calc.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.calc.CurrentPlanResult;
import com.moeun.backend.calc.CurrentPlanResultService;
import com.moeun.backend.plan.PlanInput;
import com.moeun.backend.plan.PlanSaveService;

/**
 * P-3 입력 저장 유스케이스(계약 C12a-5-2). 저장과 결과 계산을 트랜잭션 하나로 묶는다.
 * <p>plan 저장 서비스(검증 → revision 검사 → 저장 → PlanChanged 발행)와 결과 조회 서비스(계산 → calc_run 기록 →
 * display)가 이 트랜잭션에 합류한다. 계산이 실패하면 저장도 롤백된다. 저장을 먼저 커밋하면 계산할 수 없는 계획이
 * 남아 P-4 500 → 재저장 409 루프에 갇히기 때문이다. 부르는 쪽이 calc라 의존 방향(calc → plan)도 그대로다.
 * <p>커밋 뒤 PlanChanged 리스너는 이 트랜잭션이 남긴 calc_run을 보고 기록을 건너뛴다.
 */
@Service
public class SavePlanUseCase {

    private final PlanSaveService planSaveService;
    private final CurrentPlanResultService resultService;

    public SavePlanUseCase(PlanSaveService planSaveService, CurrentPlanResultService resultService) {
        this.planSaveService = planSaveService;
        this.resultService = resultService;
    }

    /**
     * @param memberId 회원(JWT subject)
     * @param input 입력 화면 값
     * @param expectedRevision 지금 가진 revision. 계획이 없다고 믿으면 null
     * @return 저장한 새 현재 계획과 그 결과
     */
    @Transactional
    public CurrentPlanResult save(long memberId, PlanInput input, Long expectedRevision) {
        planSaveService.save(memberId, input, expectedRevision);
        return resultService.findForCurrentPlan(memberId)
                .orElseThrow(() -> new IllegalStateException("saved plan is not found in the same transaction"));
    }
}
