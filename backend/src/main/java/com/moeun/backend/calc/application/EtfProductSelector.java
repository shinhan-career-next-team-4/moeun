package com.moeun.backend.calc.application;

import com.moeun.backend.plan.PlanView;
import com.moeun.backend.refdata.EtfProduct;

/**
 * 계획을 어느 ETF 종목으로 계산할지 정하는 한 곳(#150). 결과 화면·대화 비교·planContext가 모두 이 결정을 쓴다.
 * <p>계획에 종목 칸이 생기기 전(A-2 "계획에 ETF 종목 코드 저장")까지는 모든 계획이 대표 상품(S&P500 추종 ETF)이다.
 * 그 작업이 끝나면 여기서 계획의 종목 코드를 돌려주도록 바꾼다.
 */
public final class EtfProductSelector {

    private EtfProductSelector() {
    }

    /** 그 계획을 계산할 ETF 종목 코드. */
    public static String productCodeOf(PlanView plan) {
        return EtfProduct.REPRESENTATIVE_CODE;
    }
}
