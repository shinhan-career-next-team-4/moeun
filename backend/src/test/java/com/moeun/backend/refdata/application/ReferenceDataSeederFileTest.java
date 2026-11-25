package com.moeun.backend.refdata.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.application.RefDataSeedProperties.Seed;

class ReferenceDataSeederFileTest {

    private static final FileSystemResource MISSING_FILE = new FileSystemResource("build/no-such-seed-file.csv");

    private final ReferenceDataLoader loader = mock(ReferenceDataLoader.class);

    @Test
    @DisplayName("선택 시드 파일이 없으면 적재를 건너뛰고 서버 기동을 막지 않는다")
    void skipsMissingOptionalSeed() {
        ReferenceDataSeeder seeder = createSeeder(true);

        assertThatCode(() -> seeder.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        verifyNoInteractions(loader);
    }

    @Test
    @DisplayName("필수 시드 파일이 없으면 종류와 버전 이름을 담은 예외로 기동을 멈춘다")
    void failsOnMissingRequiredSeed() {
        ReferenceDataSeeder seeder = createSeeder(false);

        assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments()))
                .isInstanceOf(ReferenceDataLoadException.class)
                .hasMessageContaining("ETF_PRICE test-label")
                .hasMessageContaining("seed file not found");
        verifyNoInteractions(loader);
    }

    @Test
    @DisplayName("ETF 시드에 종목 코드·화면 이름·세율 중 하나라도 없으면 어느 시드인지 담은 예외로 기동을 멈춘다")
    void failsOnEtfSeedWithoutProductInfo() {
        Seed seed = new Seed(DataKind.ETF_PRICE, "investing-2016-10-2026-10", LocalDate.of(2026, 10, 31), "테스트",
                new ClassPathResource("refdata/test-sp500-2016-10-to-2026-10.csv"), true, "069500", null,
                BigDecimal.ZERO);
        ReferenceDataSeeder seeder = new ReferenceDataSeeder(new RefDataSeedProperties(List.of(seed)), loader);

        assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments()))
                .isInstanceOf(ReferenceDataLoadException.class)
                .hasMessageContaining("ETF_PRICE investing-2016-10-2026-10")
                .hasMessageContaining("display-name");
        verifyNoInteractions(loader);
    }

    private ReferenceDataSeeder createSeeder(boolean isOptional) {
        Seed seed = new Seed(DataKind.ETF_PRICE, "test-label", LocalDate.of(2026, 10, 31), "테스트", MISSING_FILE,
                isOptional, "SP500", "S&P500 추종 ETF", new BigDecimal("15.4"));
        return new ReferenceDataSeeder(new RefDataSeedProperties(List.of(seed)), loader);
    }
}
