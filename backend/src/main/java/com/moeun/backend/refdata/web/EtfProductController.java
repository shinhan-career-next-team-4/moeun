package com.moeun.backend.refdata.web;

import java.util.Comparator;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.ReferenceDataService;
import com.moeun.backend.refdata.application.RefDataSeedProperties;

/**
 * 고를 수 있는 ETF 종목 목록(#220). 결과 화면의 종목 바꾸기(#156)가 쓴다.
 * <p>종목은 적재된 것만 나온다. 가격 데이터가 없는 종목은 계산할 수 없으므로 빠진다. 순서는 설정
 * ({@code moeun.refdata.seeds})에 적힌 순서이고, 대표 상품(S&P500 추종 ETF)을 설정 맨 앞에 둔다.
 * 회원별 데이터가 아니라 회원을 구분하지 않지만, 다른 {@code /api/**}처럼 로그인해야 부를 수 있다(보안 설정 그대로).
 */
@RestController
class EtfProductController {

    private final ReferenceDataService referenceDataService;
    /** 설정에 적힌 ETF 종목 코드 순서. */
    private final List<String> settingsOrder;

    EtfProductController(ReferenceDataService referenceDataService, RefDataSeedProperties seedProperties) {
        this.referenceDataService = referenceDataService;
        this.settingsOrder = seedProperties.seeds().stream()
                .filter(seed -> seed.kind() == DataKind.ETF_PRICE)
                .map(RefDataSeedProperties.Seed::productCode)
                .toList();
    }

    /** 설정 순서대로. 설정에 없는 종목(예전에 적재된 종목)은 맨 뒤에 서비스 순서(종목 코드 순) 그대로 둔다. */
    @GetMapping("/api/etf-products")
    EtfProductsResponse findProducts() {
        return new EtfProductsResponse(referenceDataService.findActiveEtfProducts().stream()
                .sorted(Comparator.comparingInt(this::findSettingsIndex))
                .map(EtfProductsResponse.Product::from)
                .toList());
    }

    private int findSettingsIndex(EtfProduct product) {
        int index = settingsOrder.indexOf(product.code());
        return index < 0 ? settingsOrder.size() : index;
    }
}
