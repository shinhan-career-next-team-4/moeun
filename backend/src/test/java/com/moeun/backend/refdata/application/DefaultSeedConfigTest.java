package com.moeun.backend.refdata.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.TestPropertySource;

import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.DataVersionService;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.EtfSeries;
import com.moeun.backend.refdata.ReferenceDataService;
import com.moeun.backend.refdata.SavingsAvgRateData;

/**
 * application.yml의 기본 시드 설정을 그대로 쓰고, 레포에 없는 ETF 가격 파일 위치만 테스트용 가짜 파일로 바꾼다.
 * 종목 정보(코드, 화면 이름, 세율)는 application.yml에 적힌 값이 그대로 적재되는지 본다(#138).
 */
// TestcontainersConfig가 config 패키지에 있어서, config 모듈을 함께 포함하지 않으면 DB 컨테이너 빈이 걸러진다
@ApplicationModuleTest(extraIncludes = {"config", "auth"})
@Import(TestcontainersConfig.class)
@TestPropertySource(properties = {
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv",
    "MOEUN_ETF_069500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv",
    "MOEUN_ETF_133690_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
class DefaultSeedConfigTest {

    @Autowired
    DataVersionService dataVersionService;

    @Autowired
    ReferenceDataService referenceDataService;

    @Test
    @DisplayName("앱을 처음 기동하면 활성 기준 데이터 1세트가 생기고 S&P500은 120개월 이상이다")
    void loadsOneActiveSetOnStartup() {
        assertThat(dataVersionService.findActiveVersions()).isPresent();

        EtfSeries series = referenceDataService.findRepresentativeEtfSeries().orElseThrow();
        assertThat(series.monthlyCloses()).hasSizeGreaterThanOrEqualTo(120);
        assertThat(series.monthlyCloses().get(0).month()).isEqualTo(YearMonth.of(2016, 10));
        assertThat(series.monthlyCloses().get(series.monthlyCloses().size() - 1).month())
                .isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    @DisplayName("가정 보기용으로 S&P500 활성 데이터를 읽으면 달러 지수 기준일이 함께 나온다")
    void providesDollarIndexBaseDate() {
        EtfSeries series = referenceDataService.findRepresentativeEtfSeries().orElseThrow();

        assertThat(series.version().baseDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        // application.yml의 S&P500 version-label. CSV를 바꿔 재적재하면 라벨이 바뀐다(#327)
        assertThat(series.version().versionLabel()).isEqualTo("investing-2016-10-2026-10-r2");
        assertThat(series.version().source()).contains("달러 지수");
    }

    @Test
    @DisplayName("application.yml의 ETF 종목 3줄이 화면 이름·세율 그대로 적재된다(069500 = 0%, 나머지 15.4%)")
    void loadsThreeEtfProductsFromSettings() {
        assertThat(referenceDataService.findActiveEtfProducts()).containsExactly(
                new EtfProduct("069500", "코스피200 추종 ETF", BigDecimal.ZERO),
                new EtfProduct("133690", "나스닥100 추종 ETF", new BigDecimal("15.4")),
                new EtfProduct("SP500", "S&P500 추종 ETF", new BigDecimal("15.4")));
        for (String code : new String[] {"SP500", "069500", "133690"}) {
            EtfSeries series = referenceDataService.findActiveEtfSeries(code).orElseThrow();
            assertThat(series.monthlyCloses()).hasSize(121);
            assertThat(series.version().baseDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        }
    }

    @Test
    @DisplayName("레포에 있는 평균금리·계산 파라미터 시드가 적재된다")
    void loadsCommittedSeeds() {
        SavingsAvgRateData rate = referenceDataService.findActiveSavingsAvgRate().orElseThrow();
        assertThat(rate.baseMonth()).isEqualTo(YearMonth.of(2026, 10));
        // 은행연합회 2026-10 전월취급 평균금리, 12개월 정액적립식 단리, 10개 은행 단순평균(seed/README.md)
        assertThat(rate.avgRatePct()).isEqualByComparingTo("2.84");
        assertThat(rate.version().source()).contains("은행연합회").doesNotContain("임시값");

        CalcParams params = referenceDataService.findActiveCalcParams().orElseThrow();
        assertThat(params.values()).hasSize(CalcParamKey.values().length);
        assertThat(params.getValue(CalcParamKey.TAX_RATE_PCT)).isEqualByComparingTo("15.4");
        assertThat(params.getValue(CalcParamKey.SCENARIO_OBSERVATION_MONTHS)).isEqualByComparingTo("120");
        assertThat(params.getValue(CalcParamKey.BOOTSTRAP_PATH_COUNT)).isEqualByComparingTo("10000");
    }
}
