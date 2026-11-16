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
import com.moeun.backend.refdata.Sp500Series.MonthlyClose;
import com.moeun.backend.refdata.application.SavingsRateCsvParser.SavingsRate;
import com.moeun.backend.refdata.domain.CalcParam;
import com.moeun.backend.refdata.domain.DataVersion;
import com.moeun.backend.refdata.domain.DataVersionRepository;
import com.moeun.backend.refdata.domain.SavingsAvgRate;
import com.moeun.backend.refdata.domain.Sp500Monthly;

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
        if (dataVersionRepository.existsByDataKindAndVersionLabel(dataKind, spec.versionLabel())) {
            return Optional.empty();
        }
        DataVersion version = switch (dataKind) {
            case SP500 -> saveSp500(spec, Sp500CsvParser.parse(csv));
            case SAVINGS_RATE -> saveSavingsRate(spec, SavingsRateCsvParser.parse(csv));
            case CALC_PARAM -> saveCalcParams(spec, CalcParamCsvParser.parse(csv));
        };
        switchActiveVersion(version);
        return Optional.of(version.toInfo());
    }

    private DataVersion saveSp500(VersionSpec spec, List<MonthlyClose> monthlyCloses) {
        YearMonth lastMonth = monthlyCloses.get(monthlyCloses.size() - 1).month();
        requireBaseDateIn(spec, lastMonth);
        DataVersion version = createVersion(DataKind.SP500, spec);
        for (MonthlyClose monthlyClose : monthlyCloses) {
            entityManager.persist(new Sp500Monthly(version.getId(), monthlyClose.month().atDay(1),
                    monthlyClose.closeValue()));
        }
        return version;
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
        dataVersionRepository.findByDataKindAndIsActiveTrue(newVersion.getDataKind())
                .ifPresent(DataVersion::deactivate);
        // 종류별 활성 1개 인덱스(uq_data_version_active) 때문에 끄는 UPDATE가 켜는 UPDATE보다 먼저 나가야 한다
        dataVersionRepository.flush();
        newVersion.activate();
    }
}
