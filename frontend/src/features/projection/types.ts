/**
 * "N년 뒤 예상 자산" 누적 그래프가 받는 데이터(#298).
 *
 * 계산은 모두 서버(계산 엔진)가 한다. 이 컴포넌트는 받은 값을 그리기만 하므로 이자·수익률·합계를
 * 직접 만들지 않는다(NFR-INT-01). 그래서 합계(`total`)와 수익 합(`profit`)도 서버가 준 값을 그대로 받는다.
 * 모든 금액은 원 단위 정수이고, 누적 값이다(그 달까지 쌓인 값).
 */
export interface ProjectionPoint {
  /** 몇 개월째인지(1부터). */
  month: number;
  /** 누적 원금. */
  principal: number;
  /** 누적 적금 이자. */
  savingsInterest: number;
  /** 누적 ETF 수익. */
  etfGain: number;
  /** 이자·수익의 합(= 적금 이자 + ETF 수익). 상단의 "+수익 금액"에 쓴다. */
  profit: number;
  /** 합계(= 원금 + 수익). 상단 결론 숫자와 툴팁의 합계에 쓴다. */
  total: number;
}

/**
 * 시나리오: 좋았던 때(상위 10%) / 평균 / 나빴던 때(하위 10%).
 * 결과 화면 3구간 카드와 서버의 `scenarios[].scenario`와 같은 코드(GOOD·AVG·BAD)라 연동할 때 옮길 필요가 없다.
 */
export type ProjectionScenario = "GOOD" | "AVG" | "BAD";

/** 시나리오마다 월별 점 배열 하나씩. 최대 360개월(30년)까지 받는다. */
export type ProjectionData = Partial<Record<ProjectionScenario, ProjectionPoint[]>>;

/** 월별 / 연별. 데이터를 몇 개 골라 보여 줄지만 바뀐다. */
export type ProjectionUnit = "monthly" | "yearly";
