package com.moeun.backend.refdata.domain;

import java.math.BigDecimal;

import com.moeun.backend.refdata.EtfProduct;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 가격 버전 하나의 종목 정보. 세율을 데이터 버전에 묶어 두어, 설정이 나중에 바뀌어도 그 버전으로 한 계산은 그대로 다시 나온다.
 */
@Entity
@Table(name = "etf_product_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EtfProductVersion {

    @Id
    private Long dataVersionId;

    @Column(nullable = false, length = 20)
    private String productCode;

    @Column(nullable = false, length = 50)
    private String displayName;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal gainTaxRatePct;

    public EtfProductVersion(Long dataVersionId, EtfProduct product) {
        this.dataVersionId = dataVersionId;
        this.productCode = product.code();
        this.displayName = product.displayName();
        this.gainTaxRatePct = product.gainTaxRatePct();
    }

    public EtfProduct toProduct() {
        return new EtfProduct(productCode, displayName, gainTaxRatePct);
    }
}
