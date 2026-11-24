package com.moeun.backend.calc;

import java.util.Collection;
import java.util.Set;

import org.springframework.modulith.NamedInterface;

/**
 * 계산 기록이 ETF를 썼는지(#305). chat이 결과 설명의 ETF 위험 설명을 그 설명의 계산 기준으로 보이게 하려고 부른다.
 * 금액이 아닌 참·거짓만 돌려주고, 부르는 쪽이 자기 메시지의 calc_run_id만 넘긴다.
 */
@NamedInterface("calc-run-etf")   // chat이 계산 기록의 ETF 사용 여부를 본다(#305)
public interface CalcRunEtfQuery {

    /**
     * @param calcRunIds 확인할 calc_run id
     * @return 그중 ETF 가격 버전을 쓴 기록의 id. ETF 0%·없는 기록은 빠진다
     */
    Set<Long> findRunsUsingEtf(Collection<Long> calcRunIds);
}
