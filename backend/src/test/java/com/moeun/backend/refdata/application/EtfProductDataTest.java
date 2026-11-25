package com.moeun.backend.refdata.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;

import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionInfo;
import com.moeun.backend.refdata.DataVersionService;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.EtfSeries;
import com.moeun.backend.refdata.ReferenceDataService;

/**
 * ETF 종목별 가격 데이터와 종목 정보(#138). S&P500도 종목 하나다.
 * <p>가격은 수식으로 만든 가짜 값이다(실제 데이터는 레포에 없음).
 */
@ApplicationModuleTest(extraIncludes = {"config", "auth"})
@Import(TestcontainersConfig.class)
class EtfProductDataTest {

    private static final String SOURCE = "테스트용 가짜 데이터";
    private static final YearMonth FIRST_MONTH = YearMonth.of(2016, 10);
    private static final int MONTHS = 121;
    private static final VersionSpec V1 = new VersionSpec("investing-2016-10-2026-10", LocalDate.of(2026, 10, 31),
            SOURCE);
    private static final VersionSpec V2 = new VersionSpec("investing-2016-11-2026-11", LocalDate.of(2026, 11, 30),
            SOURCE);
    private static final EtfProduct SP500 = new EtfProduct("SP500", "S&P500 추종 ETF", new BigDecimal("15.4"));
    private static final EtfProduct KOSPI200 = new EtfProduct("069500", "코스피200 추종 ETF", BigDecimal.ZERO);
    private static final EtfProduct NASDAQ100 = new EtfProduct("133690", "나스닥100 추종 ETF", new BigDecimal("15.4"));

    @Autowired
    ReferenceDataLoader loader;

    @Autowired
    ReferenceDataService referenceDataService;

    @Autowired
    DataVersionService dataVersionService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearReferenceData() {
        jdbcTemplate.execute("TRUNCATE data_version RESTART IDENTITY CASCADE");
    }

    private DataVersionInfo load(EtfProduct product, VersionSpec spec, YearMonth firstMonth) {
        return loader.loadEtf(product, spec, CsvFixtures.toStream(CsvFixtures.createSp500Csv(firstMonth, MONTHS)))
                .orElseThrow();
    }

    @Test
    @DisplayName("종목 3개를 같은 버전 이름으로 적재하면 종목마다 활성 버전이 하나씩 생기고 종가 121개와 종목 정보를 돌려준다")
    void loadsThreeProducts() {
        load(SP500, V1, FIRST_MONTH);
        load(KOSPI200, V1, FIRST_MONTH);
        load(NASDAQ100, V1, FIRST_MONTH);

        assertThat(referenceDataService.findActiveEtfProducts()).containsExactly(KOSPI200, NASDAQ100, SP500);
        for (EtfProduct product : new EtfProduct[] {SP500, KOSPI200, NASDAQ100}) {
            EtfSeries series = referenceDataService.findActiveEtfSeries(product.code()).orElseThrow();
            assertThat(series.product()).isEqualTo(product);
            assertThat(series.version().productCode()).isEqualTo(product.code());
            assertThat(series.monthlyCloses()).hasSize(MONTHS);
        }
        assertThat(referenceDataService.findRepresentativeEtfSeries().orElseThrow().product()).isEqualTo(SP500);
    }

    @Test
    @DisplayName("같은 종목·같은 버전 이름은 다시 넣지 않는다(재기동해도 그대로)")
    void skipsSameProductAndLabel() {
        load(KOSPI200, V1, FIRST_MONTH);

        assertThat(loader.loadEtf(KOSPI200, V1,
                CsvFixtures.toStream(CsvFixtures.createSp500Csv(FIRST_MONTH, MONTHS)))).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM data_version WHERE product_code = '069500'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("한 종목의 새 버전을 켜도 다른 종목의 활성 버전은 그대로다")
    void keepsOtherProductsActive() {
        DataVersionInfo sp500 = load(SP500, V1, FIRST_MONTH);
        DataVersionInfo kospiOld = load(KOSPI200, V1, FIRST_MONTH);

        DataVersionInfo kospiNew = load(KOSPI200, V2, FIRST_MONTH.plusMonths(1));

        assertThat(dataVersionService.findActiveEtf("069500")).contains(kospiNew);
        assertThat(dataVersionService.findActiveEtf("SP500")).contains(sp500);
        assertThat(kospiNew.id()).isNotEqualTo(kospiOld.id());
    }

    @Test
    @DisplayName("기록된 옛 버전 id로 꺼내면 그때의 종가와 종목 정보(세율)가 나온다")
    void readsInactiveVersionWithItsProductInfo() {
        DataVersionInfo old = load(KOSPI200, V1, FIRST_MONTH);
        EtfProduct taxedLater = new EtfProduct("069500", "코스피200 추종 ETF", new BigDecimal("15.4"));
        load(taxedLater, V2, FIRST_MONTH.plusMonths(1));

        EtfSeries series = referenceDataService.requireEtfSeries(old.id());

        assertThat(series.product().gainTaxRatePct()).isEqualByComparingTo("0");
        assertThat(series.monthlyCloses().get(0).month()).isEqualTo(FIRST_MONTH);
        assertThat(referenceDataService.findActiveEtfSeries("069500").orElseThrow().product().gainTaxRatePct())
                .isEqualByComparingTo("15.4");
    }

    @Test
    @DisplayName("종가가 121개보다 적은 종목은 적재가 실패하고 그 종목의 이전 활성 버전이 남는다")
    void rejectsShortHistory() {
        DataVersionInfo old = load(NASDAQ100, V1, FIRST_MONTH);

        assertThatThrownBy(() -> loader.loadEtf(NASDAQ100, V2,
                CsvFixtures.toStream(CsvFixtures.createSp500Csv(YearMonth.of(2021, 12), 60))))
                .isInstanceOf(ReferenceDataLoadException.class)
                .hasMessageContaining("at least 121 months");
        assertThat(dataVersionService.findActiveEtf("133690")).contains(old);
    }

    @Test
    @DisplayName("데이터를 적재하지 않은 종목은 비어 있다")
    void isEmptyForUnloadedProduct() {
        load(SP500, V1, FIRST_MONTH);

        assertThat(referenceDataService.findActiveEtfSeries("069500")).isEmpty();
        assertThat(referenceDataService.findActiveEtfProducts()).containsExactly(SP500);
    }

    @Test
    @DisplayName("ETF 가격 버전은 종목 없이 적재·조회할 수 없다")
    void requiresProductForEtfPrice() {
        assertThatThrownBy(() -> loader.load(DataKind.ETF_PRICE, V1,
                CsvFixtures.toStream(CsvFixtures.createSp500Csv(FIRST_MONTH, MONTHS))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dataVersionService.findActive(DataKind.ETF_PRICE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
