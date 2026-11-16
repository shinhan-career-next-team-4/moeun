package com.moeun.backend.refdata.application;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

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
        VersionSpec spec = new VersionSpec(seed.versionLabel(), seed.baseDate(), seed.source());
        try (InputStream csv = seed.file().getInputStream()) {
            referenceDataLoader.load(seed.kind(), spec, csv).ifPresentOrElse(
                    version -> log.info("refdata.loaded kind={} versionLabel={} id={}", version.dataKind(),
                            version.versionLabel(), version.id()),
                    () -> log.info("refdata.skipped kind={} versionLabel={} (already loaded)", seed.kind(),
                            seed.versionLabel()));
        } catch (IOException exception) {
            throw new ReferenceDataLoadException("cannot open seed file for " + seed.kind(), exception);
        } catch (ReferenceDataLoadException exception) {
            // 어느 파일인지 알 수 있게 종류와 버전 이름을 덧붙인다
            throw new ReferenceDataLoadException(
                    seed.kind() + " " + seed.versionLabel() + ": " + exception.getMessage(), exception);
        }
    }
}
