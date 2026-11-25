package com.moeun.backend.refdata;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.refdata.domain.DataVersion;
import com.moeun.backend.refdata.domain.DataVersionRepository;

import lombok.RequiredArgsConstructor;

/** 기준 데이터 버전 조회와 종류 확인. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DataVersionService {

    private final DataVersionRepository dataVersionRepository;

    /**
     * 종류별로 지금 계산에 쓰는 버전(금리, 계산 파라미터). 아직 적재하지 않았으면 비어 있다.
     * ETF 가격은 종목마다 활성 버전이 있어 {@link #findActiveEtf(String)}를 쓴다.
     */
    public Optional<DataVersionInfo> findActive(DataKind dataKind) {
        if (dataKind == DataKind.ETF_PRICE) {
            throw new IllegalArgumentException("ETF price versions are per product, use findActiveEtf");
        }
        return dataVersionRepository.findByDataKindAndIsActiveTrue(dataKind).map(DataVersion::toInfo);
    }

    /** 그 종목의 지금 활성인 가격 버전. 아직 적재하지 않았으면 비어 있다. */
    public Optional<DataVersionInfo> findActiveEtf(String productCode) {
        return dataVersionRepository.findByDataKindAndProductCodeAndIsActiveTrue(DataKind.ETF_PRICE, productCode)
                .map(DataVersion::toInfo);
    }

    /** 지금 활성인 ETF 가격 버전들(종목 코드 순). */
    public List<DataVersionInfo> findActiveEtfVersions() {
        return dataVersionRepository.findByDataKindAndIsActiveTrueOrderByProductCodeAsc(DataKind.ETF_PRICE).stream()
                .map(DataVersion::toInfo)
                .toList();
    }

    /**
     * 금리·대표 ETF(S&P500)·계산 파라미터의 활성 버전을 한 번에 꺼낸다.
     * 하나라도 없으면 계산할 수 없는 상태라 일부만 채워 돌려주지 않고 비워 둔다.
     */
    public Optional<ActiveDataVersions> findActiveVersions() {
        return findActive(DataKind.SAVINGS_RATE)
                .flatMap(savingsRate -> findActiveEtf(EtfProduct.REPRESENTATIVE_CODE)
                        .flatMap(etfPrice -> findActive(DataKind.CALC_PARAM)
                                .map(calcParam -> new ActiveDataVersions(savingsRate, etfPrice, calcParam))));
    }

    /**
     * 버전이 기대한 종류인지 확인한다.
     * DB가 종류 일치를 막지 않으므로(ADR-002), 금리 자리에 지수 버전이 기록되지 않게 기록 전에 부른다.
     *
     * @throws IllegalArgumentException 버전이 없거나 종류가 다를 때
     */
    public DataVersionInfo requireKind(long dataVersionId, DataKind expectedKind) {
        DataVersion version = dataVersionRepository.findById(dataVersionId)
                .orElseThrow(() -> new IllegalArgumentException("data version not found: " + dataVersionId));
        if (version.getDataKind() != expectedKind) {
            throw new IllegalArgumentException("data version " + dataVersionId + " is " + version.getDataKind()
                    + ", expected " + expectedKind);
        }
        return version.toInfo();
    }
}
