package com.moeun.backend.refdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;

import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.refdata.application.ReferenceDataLoadException;
import com.moeun.backend.refdata.application.ReferenceDataLoader;
import com.moeun.backend.refdata.application.VersionSpec;

// TestcontainersConfig가 config 패키지에 있어서, config 모듈을 함께 포함하지 않으면 DB 컨테이너 빈이 걸러진다
@ApplicationModuleTest(extraIncludes = "config")
@Import(TestcontainersConfig.class)
class DataVersionServiceTest {

    private static final String SOURCE = "테스트용 가짜 데이터";
    private static final VersionSpec SP500_SPEC = new VersionSpec("test-2025-12", LocalDate.of(2025, 12, 31), SOURCE);
    private static final VersionSpec RATE_SPEC = new VersionSpec("test-2025-12", LocalDate.of(2025, 12, 1), SOURCE);
    private static final VersionSpec PARAM_V1 = new VersionSpec("test-v1", LocalDate.of(2025, 12, 1), SOURCE);
    private static final VersionSpec PARAM_V2 = new VersionSpec("test-v2", LocalDate.of(2025, 12, 1), SOURCE);

    @Autowired
    DataVersionService dataVersionService;

    @Autowired
    ReferenceDataLoader loader;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearReferenceData() {
        jdbcTemplate.execute("TRUNCATE data_version RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("세 종류가 모두 활성이면 적금 금리·지수·파라미터 활성 버전 3개를 한 번에 돌려준다")
    void findsAllThreeActiveVersions() throws Exception {
        DataVersionInfo sp500 = loadFile(DataKind.SP500, SP500_SPEC, "refdata/test-sp500-monthly.csv");
        DataVersionInfo savingsRate = loadFile(DataKind.SAVINGS_RATE, RATE_SPEC, "refdata/test-savings-avg-rate.csv");
        DataVersionInfo calcParam = loadFile(DataKind.CALC_PARAM, PARAM_V1, "refdata/test-calc-param.csv");

        assertThat(dataVersionService.findActiveVersions())
                .contains(new ActiveDataVersions(savingsRate, sp500, calcParam));
    }

    @Test
    @DisplayName("한 종류라도 활성 버전이 없으면 비어 있다")
    void isEmptyWhenAnyKindIsMissing() throws Exception {
        loadFile(DataKind.SP500, SP500_SPEC, "refdata/test-sp500-monthly.csv");
        loadFile(DataKind.CALC_PARAM, PARAM_V1, "refdata/test-calc-param.csv");

        assertThat(dataVersionService.findActiveVersions()).isEmpty();
    }

    @Test
    @DisplayName("필수 파라미터 키가 빠진 새 버전은 적재에 실패하고 이전 활성 버전이 그대로 남는다")
    void keepsPreviousActiveVersionWhenRequiredKeyIsMissing() throws Exception {
        DataVersionInfo v1 = loadFile(DataKind.CALC_PARAM, PARAM_V1, "refdata/test-calc-param.csv");
        String missingTaxRate = "param_key,param_value\n"
                + "DEFAULT_SAVINGS_RATIO_PCT,50\n"
                + "SCENARIO_OBSERVATION_MONTHS,120\n"
                + "SCENARIO_LOW_PERCENTILE,10\n"
                + "SCENARIO_HIGH_PERCENTILE,90\n"
                + "BOOTSTRAP_PATH_COUNT,10000\n"
                + "BOOTSTRAP_SEED,20261117\n";

        assertThatThrownBy(() -> loader.load(DataKind.CALC_PARAM, PARAM_V2, toStream(missingTaxRate)))
                .isInstanceOf(ReferenceDataLoadException.class)
                .hasMessageContaining("required param keys are missing: [TAX_RATE_PCT]");

        assertThat(dataVersionService.findActive(DataKind.CALC_PARAM)).contains(v1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM data_version WHERE data_kind = 'CALC_PARAM'",
                Integer.class)).isEqualTo(1);
    }

    private DataVersionInfo loadFile(DataKind kind, VersionSpec spec, String classpathFile) throws Exception {
        try (InputStream csv = new ClassPathResource(classpathFile).getInputStream()) {
            return loader.load(kind, spec, csv).orElseThrow();
        }
    }

    private static InputStream toStream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
