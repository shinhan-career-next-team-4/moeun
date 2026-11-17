package com.moeun.backend.refdata.application;

import static com.moeun.backend.refdata.application.CsvFixtures.toStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import java.io.IOException;
import java.io.InputStream;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionInfo;
import com.moeun.backend.refdata.DataVersionService;
import com.moeun.backend.refdata.ReferenceDataService;
import com.moeun.backend.refdata.SavingsAvgRateData;
import com.moeun.backend.refdata.Sp500Series;
import com.moeun.backend.refdata.domain.DataVersionRepository;

// TestcontainersConfig가 config 패키지에 있어서, config 모듈을 함께 포함하지 않으면 DB 컨테이너 빈이 걸러진다
@ApplicationModuleTest(extraIncludes = "config")
@Import(TestcontainersConfig.class)
class ReferenceDataLoaderTest {

    private static final String RATE_HEADER = "base_month,avg_rate_pct\n";
    private static final VersionSpec SP500_SPEC =
            new VersionSpec("test-2025-12", LocalDate.of(2025, 12, 31), "테스트용 가짜 데이터");
    private static final VersionSpec RATE_NOVEMBER =
            new VersionSpec("test-2025-11", LocalDate.of(2025, 11, 1), "테스트용 가짜 데이터");
    private static final VersionSpec RATE_DECEMBER =
            new VersionSpec("test-2025-12", LocalDate.of(2025, 12, 1), "테스트용 가짜 데이터");
    private static final VersionSpec PARAM_SPEC =
            new VersionSpec("test-v1", LocalDate.of(2025, 12, 1), "테스트용 임시값");

    @Autowired
    ReferenceDataLoader loader;

    @Autowired
    ReferenceDataService referenceDataService;

    @Autowired
    DataVersionService dataVersionService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    DataVersionRepository dataVersionRepository;

    @BeforeEach
    void clearReferenceData() {
        // 롤백 동작을 실제 커밋 단위로 보려고 테스트에 트랜잭션을 걸지 않는다. 대신 매번 비운다
        jdbcTemplate.execute("TRUNCATE data_version RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("S&P500 파일을 적재하면 그 버전이 활성이 되고 월 순서로 조회된다")
    void loadsSp500AndActivates() throws IOException {
        DataVersionInfo version = loadTestFile(DataKind.SP500, SP500_SPEC, "refdata/test-sp500-monthly.csv");

        Sp500Series series = referenceDataService.findActiveSp500Series().orElseThrow();
        assertThat(series.version()).isEqualTo(version);
        assertThat(series.version().baseDate()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(series.monthlyCloses()).hasSize(132);
        assertThat(series.monthlyCloses().get(0).month()).isEqualTo(YearMonth.of(2015, 1));
        assertThat(series.monthlyCloses().get(131).month()).isEqualTo(YearMonth.of(2025, 12));
        assertThat(series.monthlyCloses().get(0).closeValue()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("평균금리와 계산 파라미터를 적재하면 값으로 조회된다")
    void loadsSavingsRateAndCalcParams() throws IOException {
        loadTestFile(DataKind.SAVINGS_RATE, RATE_DECEMBER, "refdata/test-savings-avg-rate.csv");
        loadTestFile(DataKind.CALC_PARAM, PARAM_SPEC, "refdata/test-calc-param.csv");

        SavingsAvgRateData rate = referenceDataService.findActiveSavingsAvgRate().orElseThrow();
        assertThat(rate.baseMonth()).isEqualTo(YearMonth.of(2025, 12));
        assertThat(rate.avgRatePct()).isEqualByComparingTo("3.25");

        CalcParams params = referenceDataService.findActiveCalcParams().orElseThrow();
        assertThat(params.values()).hasSize(CalcParamKey.values().length);
        assertThat(params.getValue(CalcParamKey.TAX_RATE_PCT)).isEqualByComparingTo("15.4");
        assertThat(params.getValue(CalcParamKey.SCENARIO_OBSERVATION_MONTHS)).isEqualByComparingTo("120");
    }

    @Test
    @DisplayName("아무것도 적재하지 않았으면 활성 데이터가 없다")
    void hasNoActiveDataBeforeLoading() {
        assertThat(dataVersionService.findActive(DataKind.SP500)).isEmpty();
        assertThat(referenceDataService.findActiveSavingsAvgRate()).isEmpty();
        assertThat(referenceDataService.findActiveSp500Series()).isEmpty();
        assertThat(referenceDataService.findActiveCalcParams()).isEmpty();
    }

    @Test
    @DisplayName("새 버전을 적재하면 활성이 넘어가고 이전 버전의 데이터는 남는다")
    void switchesActiveVersionAndKeepsOldRows() {
        DataVersionInfo november = loadRate(RATE_NOVEMBER, "2025-11,3.10\n");
        DataVersionInfo december = loadRate(RATE_DECEMBER, "2025-12,3.25\n");

        assertThat(dataVersionService.findActive(DataKind.SAVINGS_RATE)).contains(december);
        assertThat(referenceDataService.findActiveSavingsAvgRate().orElseThrow().avgRatePct())
                .isEqualByComparingTo("3.25");
        assertThat(countRows("data_version WHERE is_active")).isEqualTo(1);
        assertThat(countRows("savings_avg_rate")).isEqualTo(2);
        assertThat(countRows("savings_avg_rate WHERE data_version_id = " + november.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("종류가 다르면 활성 버전이 서로 영향을 주지 않는다")
    void keepsActiveVersionPerKind() throws IOException {
        DataVersionInfo rate = loadRate(RATE_DECEMBER, "2025-12,3.25\n");
        DataVersionInfo params = loadTestFile(DataKind.CALC_PARAM, PARAM_SPEC, "refdata/test-calc-param.csv");

        assertThat(dataVersionService.findActive(DataKind.SAVINGS_RATE)).contains(rate);
        assertThat(dataVersionService.findActive(DataKind.CALC_PARAM)).contains(params);
    }

    @Test
    @DisplayName("같은 이름의 버전이 이미 있으면 다시 넣지 않는다")
    void skipsWhenVersionLabelExists() {
        loadRate(RATE_DECEMBER, "2025-12,3.25\n");

        assertThat(loader.load(DataKind.SAVINGS_RATE, RATE_DECEMBER, toStream(RATE_HEADER + "2025-12,9.99\n")))
                .isEmpty();
        assertThat(countRows("data_version")).isEqualTo(1);
        assertThat(referenceDataService.findActiveSavingsAvgRate().orElseThrow().avgRatePct())
                .isEqualByComparingTo("3.25");
    }

    @Test
    @DisplayName("저장 도중 실패하면 전부 취소되고 이전 활성 버전이 그대로 남는다")
    void rollsBackWhenLoadFailsMidway() {
        DataVersionInfo november = loadRate(RATE_NOVEMBER, "2025-11,3.10\n");
        // 새 버전과 금리 행을 넣은 뒤, 활성을 넘기는 시점에 실패하게 만든다
        doThrow(new IllegalStateException("test failure")).when(dataVersionRepository).flush();

        assertThatThrownBy(() -> loader.load(DataKind.SAVINGS_RATE, RATE_DECEMBER,
                toStream(RATE_HEADER + "2025-12,3.25\n")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(dataVersionService.findActive(DataKind.SAVINGS_RATE)).contains(november);
        assertThat(countRows("data_version")).isEqualTo(1);
        assertThat(countRows("savings_avg_rate")).isEqualTo(1);
    }

    @Test
    @DisplayName("파일이 규칙에 맞지 않으면 아무것도 저장하지 않는다")
    void savesNothingWhenFileIsInvalid() {
        assertThatThrownBy(() -> loader.load(DataKind.SP500, SP500_SPEC,
                toStream(CsvFixtures.createSp500Csv(YearMonth.of(2016, 1), 120))))
                .isInstanceOf(ReferenceDataLoadException.class);
        assertThatThrownBy(() -> loader.load(DataKind.CALC_PARAM, PARAM_SPEC,
                toStream("param_key,param_value\nTAX_RATE_PCT,15.4\n")))
                .isInstanceOf(ReferenceDataLoadException.class)
                .hasMessageContaining("required param keys are missing");

        assertThat(countRows("data_version")).isZero();
    }

    @Test
    @DisplayName("기준일이 데이터의 달과 다르면 거부한다")
    void rejectsBaseDateOutsideDataMonth() {
        assertThatThrownBy(() -> loader.load(DataKind.SAVINGS_RATE, RATE_NOVEMBER,
                toStream(RATE_HEADER + "2025-12,3.25\n")))
                .isInstanceOf(ReferenceDataLoadException.class)
                .hasMessage("baseDate must be in 2025-12");
        assertThat(countRows("data_version")).isZero();
    }

    @Test
    @DisplayName("버전의 종류가 기대와 같을 때만 통과시킨다")
    void requiresExpectedKind() {
        DataVersionInfo rate = loadRate(RATE_DECEMBER, "2025-12,3.25\n");

        assertThat(dataVersionService.requireKind(rate.id(), DataKind.SAVINGS_RATE)).isEqualTo(rate);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> dataVersionService.requireKind(rate.id(), DataKind.SP500))
                .withMessageContaining("expected SP500");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> dataVersionService.requireKind(rate.id() + 1000, DataKind.SAVINGS_RATE))
                .withMessageContaining("not found");
    }

    private DataVersionInfo loadRate(VersionSpec spec, String dataLine) {
        return loader.load(DataKind.SAVINGS_RATE, spec, toStream(RATE_HEADER + dataLine)).orElseThrow();
    }

    private DataVersionInfo loadTestFile(DataKind kind, VersionSpec spec, String classpathFile) throws IOException {
        try (InputStream csv = new ClassPathResource(classpathFile).getInputStream()) {
            return loader.load(kind, spec, csv).orElseThrow();
        }
    }

    private int countRows(String tableAndCondition) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + tableAndCondition, Integer.class);
    }
}
