package com.moeun.backend.refdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;

import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.refdata.application.ReferenceDataLoader;
import com.moeun.backend.refdata.application.VersionSpec;

/**
 * calc_run이 기록한 데이터 버전으로 기준 데이터를 꺼내는 메서드(#78). 활성 버전이 바뀐 뒤에도 옛 버전 값이 나와야
 * 계산 기록을 그때와 같은 결과로 다시 만들 수 있다(NFR-INT-02).
 */
@ApplicationModuleTest(extraIncludes = {"config", "auth"})
@Import(TestcontainersConfig.class)
class ReferenceDataServiceVersionTest {

    private static final EtfProduct SP500_PRODUCT = new EtfProduct("SP500", "S&P500 추종 ETF", new BigDecimal("15.4"));
    private static final String SOURCE = "테스트용 가짜 데이터";
    private static final VersionSpec V1 = new VersionSpec("test-v1", LocalDate.of(2025, 12, 1), SOURCE);
    private static final VersionSpec V2 = new VersionSpec("test-v2", LocalDate.of(2026, 1, 1), SOURCE);
    private static final VersionSpec SP500_V2 = new VersionSpec("test-v2", LocalDate.of(2026, 10, 31), SOURCE);

    @Autowired
    ReferenceDataService referenceDataService;

    @Autowired
    ReferenceDataLoader loader;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearReferenceData() {
        jdbcTemplate.execute("TRUNCATE data_version RESTART IDENTITY CASCADE");
    }

    private DataVersionInfo loadFile(DataKind kind, VersionSpec spec, String classpathFile) throws Exception {
        try (InputStream csv = new ClassPathResource(classpathFile).getInputStream()) {
            return (kind == DataKind.ETF_PRICE ? loader.loadEtf(SP500_PRODUCT, spec, csv)
                    : loader.load(kind, spec, csv)).orElseThrow();
        }
    }

    private DataVersionInfo loadText(DataKind kind, VersionSpec spec, String csv) {
        return loader.load(kind, spec, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).orElseThrow();
    }

    @Test
    @DisplayName("새 금리 버전이 활성이 된 뒤에도 옛 버전 id로 옛 금리·기준월을 꺼낸다")
    void readsInactiveSavingsRateVersion() throws Exception {
        DataVersionInfo old = loadFile(DataKind.SAVINGS_RATE, V1, "refdata/test-savings-avg-rate.csv");
        loadText(DataKind.SAVINGS_RATE, V2, "base_month,avg_rate_pct\n2026-01,2.50\n");

        SavingsAvgRateData rate = referenceDataService.requireSavingsAvgRate(old.id());

        assertThat(rate.version()).isEqualTo(old);
        assertThat(rate.baseMonth()).isEqualTo(YearMonth.of(2025, 12));
        assertThat(rate.avgRatePct()).isEqualByComparingTo("3.25");
        assertThat(referenceDataService.findActiveSavingsAvgRate().orElseThrow().avgRatePct())
                .isEqualByComparingTo("2.50");
    }

    @Test
    @DisplayName("옛 계산 파라미터 버전 id로 그 버전의 값을 꺼낸다")
    void readsInactiveCalcParamVersion() throws Exception {
        DataVersionInfo old = loadFile(DataKind.CALC_PARAM, V1, "refdata/test-calc-param.csv");
        loadText(DataKind.CALC_PARAM, V2, "param_key,param_value\n"
                + "TAX_RATE_PCT,9.9\n"
                + "DEFAULT_SAVINGS_RATIO_PCT,30\n"
                + "SCENARIO_OBSERVATION_MONTHS,120\n"
                + "SCENARIO_LOW_PERCENTILE,10\n"
                + "SCENARIO_HIGH_PERCENTILE,90\n"
                + "BOOTSTRAP_PATH_COUNT,10000\n"
                + "BOOTSTRAP_SEED,20261117\n");

        CalcParams params = referenceDataService.requireCalcParams(old.id());

        assertThat(params.version()).isEqualTo(old);
        assertThat(params.getValue(CalcParamKey.TAX_RATE_PCT)).isEqualByComparingTo(new BigDecimal("15.4"));
        assertThat(params.getValue(CalcParamKey.DEFAULT_SAVINGS_RATIO_PCT)).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("옛 S&P500 버전 id로 그 버전의 월말 종가를 시간 순서대로 꺼낸다")
    void readsInactiveSp500Version() throws Exception {
        DataVersionInfo old = loadFile(DataKind.ETF_PRICE, V1, "refdata/test-sp500-monthly.csv");
        loadFile(DataKind.ETF_PRICE, SP500_V2, "refdata/test-sp500-2016-10-to-2026-10.csv");

        EtfSeries series = referenceDataService.requireEtfSeries(old.id());

        assertThat(series.version()).isEqualTo(old);
        assertThat(series.monthlyCloses()).isNotEmpty();
        assertThat(series.monthlyCloses()).isSortedAccordingTo(
                (left, right) -> left.month().compareTo(right.month()));
        assertThat(series.monthlyCloses().size()).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM etf_monthly WHERE data_version_id = ?", Integer.class, old.id()));
    }

    @Test
    @DisplayName("없는 버전 id나 다른 종류의 버전 id면 예외다(금리 자리에 지수 버전을 넣어도 막힌다)")
    void rejectsMissingOrWrongKindVersion() throws Exception {
        DataVersionInfo params = loadFile(DataKind.CALC_PARAM, V1, "refdata/test-calc-param.csv");

        assertThatThrownBy(() -> referenceDataService.requireSavingsAvgRate(params.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> referenceDataService.requireEtfSeries(params.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> referenceDataService.requireCalcParams(9_999L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
