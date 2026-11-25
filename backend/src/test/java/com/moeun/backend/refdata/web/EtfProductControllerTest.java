package com.moeun.backend.refdata.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.ReferenceDataService;
import com.moeun.backend.refdata.application.RefDataSeedProperties;

import tools.jackson.databind.json.JsonMapper;

/** ETF 종목 목록 API(#220)의 순서·모양. 적재 여부는 refdata 서비스 몫이라 여기서는 서비스가 준 목록만 바꾼다. */
class EtfProductControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final EtfProduct KOSPI200 = new EtfProduct("069500", "코스피200 추종 ETF", BigDecimal.ZERO);
    private static final EtfProduct NASDAQ100 = new EtfProduct("133690", "나스닥100 추종 ETF", new BigDecimal("15.4"));
    private static final EtfProduct SP500 = new EtfProduct("SP500", "S&P500 추종 ETF", new BigDecimal("15.4"));

    private final ReferenceDataService referenceDataService = mock(ReferenceDataService.class);

    /** 설정(moeun.refdata.seeds)에 이 순서로 ETF 종목이 적혀 있다. 금리·파라미터 줄은 순서에 끼지 않는다. */
    private EtfProductController createController(String... etfCodes) {
        List<RefDataSeedProperties.Seed> seeds = new ArrayList<>();
        seeds.add(createSeed(DataKind.SAVINGS_RATE, null));
        Arrays.stream(etfCodes).forEach(code -> seeds.add(createSeed(DataKind.ETF_PRICE, code)));
        return new EtfProductController(referenceDataService, new RefDataSeedProperties(seeds));
    }

    private static RefDataSeedProperties.Seed createSeed(DataKind kind, String productCode) {
        return new RefDataSeedProperties.Seed(kind, "test", LocalDate.of(2026, 10, 31), "테스트",
                new ByteArrayResource(new byte[0]), true, productCode, null, null);
    }

    @Test
    @DisplayName("설정에 적힌 순서대로 준다(서비스는 종목 코드 순으로 준다)")
    void followsSettingsOrder() {
        given(referenceDataService.findActiveEtfProducts()).willReturn(List.of(KOSPI200, NASDAQ100, SP500));

        EtfProductsResponse response = createController("SP500", "069500", "133690").findProducts();

        assertThat(response.products()).extracting(EtfProductsResponse.Product::code)
                .containsExactly("SP500", "069500", "133690");
    }

    @Test
    @DisplayName("설정 순서가 바뀌면 그 순서를 따른다(종목 코드 순과 갈라지는 경우)")
    void followsChangedSettingsOrder() {
        given(referenceDataService.findActiveEtfProducts()).willReturn(List.of(KOSPI200, NASDAQ100, SP500));

        assertThat(createController("SP500", "133690", "069500").findProducts().products())
                .extracting(EtfProductsResponse.Product::code).containsExactly("SP500", "133690", "069500");
    }

    @Test
    @DisplayName("설정에 없는 종목(예전에 적재된 종목)은 맨 뒤에 종목 코드 순으로 둔다")
    void putsUnlistedProductsLast() {
        given(referenceDataService.findActiveEtfProducts()).willReturn(List.of(KOSPI200, NASDAQ100, SP500));

        assertThat(createController("SP500").findProducts().products())
                .extracting(EtfProductsResponse.Product::code).containsExactly("SP500", "069500", "133690");
    }

    @Test
    @DisplayName("(예외) 적재된 종목이 없으면 오류가 아니라 빈 목록이다")
    void returnsEmptyList() {
        given(referenceDataService.findActiveEtfProducts()).willReturn(List.of());

        assertThat(JSON.writeValueAsString(createController("SP500").findProducts()))
                .isEqualTo("{\"products\":[]}");
    }

    @Test
    @DisplayName("JSON은 code·displayName·gainTaxRatePct 세 칸이고, 세율은 숫자다(0, 15.4)")
    void writesThreeFields() {
        given(referenceDataService.findActiveEtfProducts()).willReturn(List.of(KOSPI200, SP500));

        assertThat(JSON.writeValueAsString(createController("SP500", "069500").findProducts()))
                .isEqualTo("{\"products\":["
                        + "{\"code\":\"SP500\",\"displayName\":\"S&P500 추종 ETF\",\"gainTaxRatePct\":15.4},"
                        + "{\"code\":\"069500\",\"displayName\":\"코스피200 추종 ETF\",\"gainTaxRatePct\":0}]}");
    }

    @Test
    @DisplayName("세율이 10이면 지수 표기(1E+1)가 아니라 10으로 쓴다")
    void keepsWholeNumberRatePlain() {
        given(referenceDataService.findActiveEtfProducts())
                .willReturn(List.of(new EtfProduct("TEST", "테스트 지수 추종 ETF", new BigDecimal("10.000"))));

        assertThat(JSON.writeValueAsString(createController("TEST").findProducts()))
                .contains("\"gainTaxRatePct\":10}");
    }
}
