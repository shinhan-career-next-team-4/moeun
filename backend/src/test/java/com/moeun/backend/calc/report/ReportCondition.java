package com.moeun.backend.calc.report;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.Scenario;

/**
 * 리포트의 비교 조건 하나. 계산 시작월을 고정해 언제 돌려도 같은 표가 나오게 한다.
 *
 * @param title 표 제목
 * @param input 엔진 입력(금리·비중까지 채운 값)
 */
record ReportCondition(String title, CalcInput input) {

    private static final YearMonth START_MONTH = YearMonth.of(2026, 11);

    /**
     * 기본 비교 조건 세 가지.
     * <ol>
     *     <li>기준 계획(계약 A-1): 목표 2,000만원, 36개월, 보유 500만원, 월 30만원, 적금 50 : ETF 50</li>
     *     <li>같은 조건에 ETF 100%: 종목 차이가 가장 크게 보인다</li>
     *     <li>장기: 목표 1억원, 120개월, 보유 0원, 월 60만원, 적금 50 : ETF 50</li>
     * </ol>
     *
     * @param savingsRatePct 적금 금리(%). 기본금리(은행연합회 평균)를 넣는다
     */
    static List<ReportCondition> createDefaults(BigDecimal savingsRatePct) {
        return List.of(
                new ReportCondition("기준 계획(계약 A-1)", new CalcInput(20_000_000L, 36, START_MONTH, 5_000_000L,
                        300_000L, savingsRatePct, 50, Scenario.AVG)),
                new ReportCondition("같은 조건에 ETF 100%", new CalcInput(20_000_000L, 36, START_MONTH, 5_000_000L,
                        300_000L, savingsRatePct, 0, Scenario.AVG)),
                new ReportCondition("장기 10년", new CalcInput(100_000_000L, 120, START_MONTH, 0L, 600_000L,
                        savingsRatePct, 50, Scenario.AVG)));
    }

    /**
     * 발표 데모 시나리오(#302): 전세자금 5,000만원, 36개월, 지금 가진 돈 600만원, 매달 120만원.
     * 비중은 입력 화면 기본값(적금 50 : ETF 50)이다.
     *
     * @param savingsRatePct 적금 금리(%). 기본금리(은행연합회 평균)를 넣는다
     */
    static ReportCondition createDemo(BigDecimal savingsRatePct) {
        return new ReportCondition("데모 시나리오(전세자금)", new CalcInput(50_000_000L, 36, START_MONTH, 6_000_000L,
                1_200_000L, savingsRatePct, 50, Scenario.AVG));
    }
}
