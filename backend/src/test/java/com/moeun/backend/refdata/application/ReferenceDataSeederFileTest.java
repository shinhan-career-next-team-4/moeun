package com.moeun.backend.refdata.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
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
                .hasMessageContaining("SP500 test-label")
                .hasMessageContaining("seed file not found");
        verifyNoInteractions(loader);
    }

    private ReferenceDataSeeder createSeeder(boolean isOptional) {
        Seed seed = new Seed(DataKind.SP500, "test-label", LocalDate.of(2026, 10, 31), "테스트", MISSING_FILE,
                isOptional);
        return new ReferenceDataSeeder(new RefDataSeedProperties(List.of(seed)), loader);
    }
}
