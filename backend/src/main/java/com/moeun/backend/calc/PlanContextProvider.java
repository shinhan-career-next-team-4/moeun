package com.moeun.backend.calc;

import java.util.Optional;

import org.springframework.modulith.NamedInterface;

/**
 * planContext를 만드는 calc의 공개 API(계약 C1a-10, #78). chat 모듈은 이 인터페이스만 부른다.
 * <p>소유권은 2단계로 확인한다. calc_run에는 회원 키가 없으므로 ① calc_run을 PLAN 종류로 찾고 ② 그 계획 버전을 plan 공개 API
 * {@code findVersion(memberId, ...)}로 다시 찾는다. plan 테이블을 JOIN하지 않는다. 없는 기록·남의 기록·CHAT 기록은 모두
 * 같은 빈 값이라 어느 경우인지 밖에서 구분할 수 없다(id 추측 방지).
 * <p>계산·기준 데이터 오류는 빈 값이 아니라 예외로 올라온다. chat 리스너는 그때 ai-server를 부르지 않고 실패로 기록한다.
 * QUESTION 경로({@link #findForCurrentPlan})만 calc_run(PLAN)을 기록할 수 있어 이름에 Query를 쓰지 않는다.
 */
@NamedInterface("plan-context")   // chat이 planContext를 받는다. 시그니처의 뷰·시나리오 타입도 함께 열린다
public interface PlanContextProvider {

    /**
     * 설명 요청(F-3) 시점에 내 PLAN 기록인지와 기준 시나리오만 확인한다. 재계산하지 않는다.
     *
     * @return 없는 기록·남의 기록·PLAN이 아닌 기록이면 빈 값
     */
    Optional<PlanRunRef> findPlanRun(long memberId, long calcRunId);

    /**
     * EXPLAIN: 그 기록의 계획 버전과 기준 데이터 버전으로 다시 계산해 만든다. 현재 계획이 그 뒤 바뀌었어도 그 기록 기준이다.
     *
     * @param baselineScenario chat이 F-3 때 저장한 기준 시나리오. {@code result.baselineScenario}가 이 값이 된다
     * @return 없는 기록·남의 기록·PLAN이 아닌 기록이면 빈 값
     */
    Optional<PlanContextView> findForCalcRun(long memberId, long calcRunId, Scenario baselineScenario);

    /**
     * QUESTION: 현재 계획의 최신 PLAN 기록으로 만든다. 결과 화면과 같은 서비스를 불러 기록이 없으면 그때 한 번 기록한다.
     *
     * @return 저장된 계획이 없으면 빈 값(chat이 {@code planContext: null}로 보낸다)
     */
    Optional<PlanContextView> findForCurrentPlan(long memberId);
}
