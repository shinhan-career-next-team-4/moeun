package com.moeun.backend.calc;

/**
 * 계산에 반영하지 않은 항목(FR-RES-06 가정 보기).
 * 화면 문구는 문구표(copy.ko.yml)가 맡으므로 여기에는 키만 둔다(CP-05).
 */
public enum ExcludedItem {
    /** ETF 총보수. MVP에서는 반영하지 않는다(TBD-03). */
    ETF_TOTAL_EXPENSE,
    /** 환율. 지수 수익률을 그대로 쓴다. */
    EXCHANGE_RATE,
    /** ETF 분배금과 재투자(TBD-02, TBD-05). */
    ETF_DISTRIBUTION,
    /** 보유 자산의 운용 수익. 원금에만 더한다(CP-03, TBD-06). */
    HELD_ASSET_RETURN,
    /** 적금 만기 후 재예치(TBD-04). */
    SAVINGS_REDEPOSIT
}
