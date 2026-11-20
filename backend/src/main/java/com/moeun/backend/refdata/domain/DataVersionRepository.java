package com.moeun.backend.refdata.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.moeun.backend.refdata.DataKind;

public interface DataVersionRepository extends JpaRepository<DataVersion, Long> {

    /** 종목이 없는 종류(금리, 계산 파라미터)의 활성 버전. */
    Optional<DataVersion> findByDataKindAndIsActiveTrue(DataKind dataKind);

    /** ETF 종목의 활성 버전. 활성 버전은 종목마다 1개다. */
    Optional<DataVersion> findByDataKindAndProductCodeAndIsActiveTrue(DataKind dataKind, String productCode);

    /** 지금 활성인 ETF 종목 버전들. */
    List<DataVersion> findByDataKindAndIsActiveTrueOrderByProductCodeAsc(DataKind dataKind);

    boolean existsByDataKindAndVersionLabel(DataKind dataKind, String versionLabel);

    boolean existsByDataKindAndProductCodeAndVersionLabel(DataKind dataKind, String productCode, String versionLabel);
}
