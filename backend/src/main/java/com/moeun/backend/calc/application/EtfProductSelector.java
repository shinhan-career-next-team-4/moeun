package com.moeun.backend.calc.application;

import com.moeun.backend.plan.PlanView;

/**
 * 계획을 어느 ETF 종목으로 계산할지 정하는 한 곳(#150). 결과 화면·대화 비교·대안이 모두 이 결정을 쓴다.
 * <p>계획에 저장된 종목 코드를 쓴다(#154). 새 계획과 종목 칸이 없던 옛 계획은 대표 상품(S&P500 추종 ETF)이다.
 * 저장된 기록을 다시 계산하는 planContext는 이 결정 대신 calc_run이 가리키는 가격 버전을 쓴다.
 */
public final class EtfProductSelector {

    private EtfProductSelector() {
    }

    /** 그 계획을 계산할 ETF 종목 코드. */
    public static String productCodeOf(PlanView plan) {
        return plan.etfProductCode();
    }
}
