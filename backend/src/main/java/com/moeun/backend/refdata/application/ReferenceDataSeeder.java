package com.moeun.backend.refdata.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionInfo;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.application.RefDataSeedProperties.Seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 서버가 뜰 때 설정에 적힌 기준 데이터를 적재한다.
 * 파일이 잘못됐으면 예외를 그대로 올려 기동을 멈춘다. 틀린 기준 데이터로 계산하는 것보다 서버가 안 뜨는 편이 낫다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(RefDataSeedProperties.class)
public class ReferenceDataSeeder implements ApplicationRunner {

    private final RefDataSeedProperties properties;
    private final ReferenceDataLoader referenceDataLoader;

    @Override
    public void run(ApplicationArguments args) {
        for (Seed seed : properties.seeds()) {
            loadSeed(seed);
        }
    }

    private void loadSeed(Seed seed) {
        // 종목 설정은 파일이 없는 환경에서도 검사한다. 그래야 설정 실수가 파일을 둔 사람의 서버에서만 드러나지 않는다
        EtfProduct product = seed.kind() == DataKind.ETF_PRICE ? requireProduct(seed) : null;
        if (!seed.file().exists()) {
            skipOrFailMissingFile(seed);
            return;
        }
        VersionSpec spec = new VersionSpec(seed.versionLabel(), seed.baseDate(), seed.source());
        try (InputStream csv = seed.file().getInputStream()) {
            Optional<DataVersionInfo> loaded = seed.kind() == DataKind.ETF_PRICE
                    ? referenceDataLoader.loadEtf(product, spec, csv)
                    : referenceDataLoader.load(seed.kind(), spec, csv);
            loaded.ifPresentOrElse(
                    version -> log.info("refdata.loaded kind={} product={} versionLabel={} id={}", version.dataKind(),
                            version.productCode(), version.versionLabel(), version.id()),
                    () -> log.info("refdata.skipped kind={} product={} versionLabel={} (already loaded)", seed.kind(),
                            seed.productCode(), seed.versionLabel()));
        } catch (IOException exception) {
            throw new ReferenceDataLoadException("cannot open seed file for " + seed.kind(), exception);
        } catch (ReferenceDataLoadException exception) {
            // 어느 파일인지 알 수 있게 종류와 버전 이름을 덧붙인다
            throw new ReferenceDataLoadException(
                    seed.kind() + " " + seed.versionLabel() + ": " + exception.getMessage(), exception);
        }
    }

    /** ETF 종목 정보는 설정 한 줄에서 온다. 셋 중 하나라도 없으면 그 종목을 적재하지 않고 기동을 막는다. */
    private static EtfProduct requireProduct(Seed seed) {
        if (seed.productCode() == null || seed.productCode().isBlank() || seed.displayName() == null
                || seed.displayName().isBlank() || seed.gainTaxRatePct() == null) {
            throw new ReferenceDataLoadException(seed.kind() + " " + seed.versionLabel()
                    + ": ETF_PRICE seed needs product-code, display-name, gain-tax-rate-pct");
        }
        return new EtfProduct(seed.productCode(), seed.displayName(), seed.gainTaxRatePct());
    }

    // ETF 가격은 이용 조건 때문에 레포에 올리지 않아(TBD-08) 파일이 없는 환경이 정상이다. 그 경우만 건너뛴다
    private void skipOrFailMissingFile(Seed seed) {
        if (!seed.optional()) {
            throw new ReferenceDataLoadException(seed.kind() + " " + seed.versionLabel() + ": seed file not found");
        }
        log.warn("refdata.missing kind={} product={} versionLabel={} (optional seed file not found, "
                + "see seed/README.md)", seed.kind(), seed.productCode(), seed.versionLabel());
    }
}
