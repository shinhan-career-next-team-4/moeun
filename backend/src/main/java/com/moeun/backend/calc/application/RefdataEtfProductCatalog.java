package com.moeun.backend.calc.application;

import org.springframework.stereotype.Component;

import com.moeun.backend.plan.EtfProductCatalog;
import com.moeun.backend.refdata.ReferenceDataService;

/**
 * 계획에 고를 수 있는 ETF 종목 = refdata에 활성 가격 데이터가 있는 종목(#154). 허용 종목을 설정에 따로 적지 않는다.
 * plan이 refdata에 의존하지 않도록 plan의 {@link EtfProductCatalog}를 calc가 구현한다.
 */
@Component
class RefdataEtfProductCatalog implements EtfProductCatalog {

    private final ReferenceDataService referenceDataService;

    RefdataEtfProductCatalog(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

    @Override
    public boolean isAvailable(String etfProductCode) {
        return etfProductCode != null && referenceDataService.findActiveEtfSeries(etfProductCode).isPresent();
    }
}
