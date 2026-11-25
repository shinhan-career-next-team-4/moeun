package com.moeun.backend.refdata.application;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionInfo;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.EtfSeries.MonthlyClose;
import com.moeun.backend.refdata.application.SavingsRateCsvParser.SavingsRate;
import com.moeun.backend.refdata.domain.CalcParam;
import com.moeun.backend.refdata.domain.DataVersion;
import com.moeun.backend.refdata.domain.DataVersionRepository;
import com.moeun.backend.refdata.domain.EtfMonthly;
import com.moeun.backend.refdata.domain.EtfProductVersion;
import com.moeun.backend.refdata.domain.SavingsAvgRate;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * 기준 데이터 파일 하나를 새 버전으로 적재하고 그 버전을 켠다.
 * 검증, 저장, 활성 전환이 한 트랜잭션이라 도중에 실패하면 이전 활성 버전이 그대로 남는다.
 */
@Service
@RequiredArgsConstructor
public class ReferenceDataLoader {

    private final DataVersionRepository dataVersionRepository;
    private final EntityManager entityManager;

    /**
     * 적재한다.
     *
     * @return 새로 만든 버전. 같은 이름의 버전이 이미 있으면 비어 있다(재기동해도 다시 넣지 않는다)
     * @throws ReferenceDataLoadException 파일 내용이 규칙에 맞지 않을 때
     */
    @Transactional
    public Optional<DataVersionInfo> load(DataKind dataKind, VersionSpec spec, InputStream csv) {
        if (dataKind == DataKind.ETF_PRICE) {
            throw new IllegalArgumentException("ETF price needs product info, use loadEtf");
        }
        if (dataVersionRepository.existsByDataKindAndVersionLabel(dataKind, spec.versionLabel())) {
            return Optional.empty();
        }
        DataVersion version = switch (dataKind) {
            case SAVINGS_RATE -> saveSavingsRate(spec, SavingsRateCsvParser.parse(csv));
            case CALC_PARAM -> saveCalcParams(spec, CalcParamCsvParser.parse(csv));
            case ETF_PRICE -> throw new IllegalStateException("unreachable");
        };
        switchActiveVersion(version);
        return Optional.of(version.toInfo());
    }

    /**
     * ETF 종목 하나의 월말 종가를 새 버전으로 적재하고 그 종목의 활성 버전으로 켠다. 다른 종목의 활성 버전은 그대로다.
     * 종목 정보(화면 이름, 매매차익 세율)를 같은 버전에 함께 저장한다.
     *
     * @return 새로 만든 버전. 그 종목에 같은 이름의 버전이 이미 있으면 비어 있다
     * @throws ReferenceDataLoadException 파일 내용이 규칙에 맞지 않을 때(종가 121개 미만, 빠진 달 등)
     */
    @Transactional
    public Optional<DataVersionInfo> loadEtf(EtfProduct product, VersionSpec spec, InputStream csv) {
        if (dataVersionRepository.existsByDataKindAndProductCodeAndVersionLabel(DataKind.ETF_PRICE, product.code(),
                spec.versionLabel())) {
            return Optional.empty();
        }
        List<MonthlyClose> monthlyCloses = EtfPriceCsvParser.parse(csv);
        requireBaseDateIn(spec, monthlyCloses.get(monthlyCloses.size() - 1).month());
        DataVersion version = dataVersionRepository.save(DataVersion.createInactiveEtf(product.code(),
                spec.versionLabel(), spec.baseDate(), spec.source()));
        entityManager.persist(new EtfProductVersion(version.getId(), product));
        for (MonthlyClose monthlyClose : monthlyCloses) {
            entityManager.persist(new EtfMonthly(version.getId(), monthlyClose.month().atDay(1),
                    monthlyClose.closeValue()));
        }
        switchActiveVersion(version);
        return Optional.of(version.toInfo());
    }

    private DataVersion saveSavingsRate(VersionSpec spec, SavingsRate savingsRate) {
        requireBaseDateIn(spec, savingsRate.baseMonth());
        DataVersion version = createVersion(DataKind.SAVINGS_RATE, spec);
        entityManager.persist(new SavingsAvgRate(version.getId(), savingsRate.baseMonth().atDay(1),
                savingsRate.avgRatePct()));
        return version;
    }

    private DataVersion saveCalcParams(VersionSpec spec, Map<CalcParamKey, BigDecimal> values) {
        CalcParamLoader.validateRequiredKeys(values);
        DataVersion version = createVersion(DataKind.CALC_PARAM, spec);
        values.forEach((key, value) -> entityManager.persist(new CalcParam(version.getId(), key, value)));
        return version;
    }

    private DataVersion createVersion(DataKind dataKind, VersionSpec spec) {
        return dataVersionRepository.save(
                DataVersion.createInactive(dataKind, spec.versionLabel(), spec.baseDate(), spec.source()));
    }

    // 가정 보기에 나가는 기준일이 실제 데이터와 어긋나지 않게 한다
    private static void requireBaseDateIn(VersionSpec spec, YearMonth dataMonth) {
        if (!YearMonth.from(spec.baseDate()).equals(dataMonth)) {
            throw new ReferenceDataLoadException("baseDate must be in " + dataMonth);
        }
    }

    private void switchActiveVersion(DataVersion newVersion) {
        Optional<DataVersion> current = newVersion.getDataKind() == DataKind.ETF_PRICE
                ? dataVersionRepository.findByDataKindAndProductCodeAndIsActiveTrue(DataKind.ETF_PRICE,
                        newVersion.getProductCode())
                : dataVersionRepository.findByDataKindAndIsActiveTrue(newVersion.getDataKind());
        current.ifPresent(DataVersion::deactivate);
        // 활성 1개 인덱스(uq_data_version_active_product) 때문에 끄는 UPDATE가 켜는 UPDATE보다 먼저 나가야 한다
        dataVersionRepository.flush();
        newVersion.activate();
    }
}
