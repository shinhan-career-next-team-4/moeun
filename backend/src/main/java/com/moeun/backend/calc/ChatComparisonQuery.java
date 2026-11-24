package com.moeun.backend.calc;

import java.util.Map;
import java.util.Optional;

import org.springframework.modulith.NamedInterface;

import tools.jackson.databind.JsonNode;

/**
 * 대화 비교(F-4·F-5)를 위해 calc_run(CHAT) 기록을 읽는 calc의 공개 API(계약 H-8b, C12b-6). 기록을 새로 남기지 않는다.
 * <p>소유권 확인은 {@link PlanContextProvider}와 같다(calc_run을 CHAT으로 찾고 plan 공개 API {@code findVersion}).
 * 없는 기록·남의 기록·PLAN 기록은 모두 빈 값이다.
 */
@NamedInterface("chat-comparison")   // chat이 대화 비교를 다시 계산한다. 비교·결과 타입도 함께 열린다
public interface ChatComparisonQuery {

    /**
     * CHAT 기록의 기준 계획 버전 id만 돌려준다. 재계산하지 않는다(F-5 검사 4).
     *
     * @return 없는·남의·PLAN 기록이면 빈 값
     */
    Optional<Long> findChatRunPlanVersion(long memberId, long calcRunId);

    /**
     * CHAT 기록의 계획 버전·기준 데이터 버전에 그때의 바꾼 조건을 얹어 current·changed를 다시 계산한다. 그 뒤 활성 데이터
     * 버전이 바뀌어도 기록의 버전을 쓴다. 범위 검사는 하지 않는다(저장된 대화의 값은 이미 검사를 통과했다). 타입만 바꾼다.
     * <p>기준 시나리오는 calc_run에 없어 지금 계획의 값이다. 기준 시나리오를 바꾸는 기능이 생기면 인자로 받아야 한다.
     *
     * @param conditionChanges 대화 메시지에 저장된 바꾼 조건. 키는 대화로 바꿀 수 있는 6개(goalAmount, monthlySavingAmount,
     *     savingsRatioPct, savingsRateInputPct, targetPeriodMonths는 숫자, etfProductCode는 문자열, D-097). null 값은
     *     무시한다. 종목을 바꾼 기록이면 changed는 calc_run에 남긴 그 종목의 가격 버전으로 계산한다
     * @return 없는·남의·PLAN 기록이면 빈 값
     * @throws IllegalArgumentException 허용 6개 밖의 키, 타입이 맞지 않는 값, 정수 자리의 소수처럼 타입을 바꿀 수 없을 때
     */
    Optional<ChatComparisonView> recompute(long memberId, long calcRunId, Map<String, JsonNode> conditionChanges);
}
