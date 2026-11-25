package com.moeun.backend.refdata.application;

import static com.moeun.backend.refdata.application.CsvFixtures.toStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.refdata.CalcParamKey;

class CalcParamCsvParserTest {

    private static final String HEADER = "param_key,param_value\n";
    private static final String ALL_KEYS = HEADER
            + "TAX_RATE_PCT,15.4\n"
            + "DEFAULT_SAVINGS_RATIO_PCT,50\n"
            + "SCENARIO_OBSERVATION_MONTHS,120\n"
            + "SCENARIO_LOW_PERCENTILE,10\n"
            + "SCENARIO_HIGH_PERCENTILE,90\n"
            + "BOOTSTRAP_PATH_COUNT,10000\n"
            + "BOOTSTRAP_SEED,20261117\n";

    @Test
    @DisplayName("항목별 값을 읽고 필수 항목 검사를 통과한다")
    void parsesAllKeys() {
        Map<CalcParamKey, BigDecimal> values = CalcParamCsvParser.parse(toStream(ALL_KEYS));

        assertThat(values).hasSize(CalcParamKey.values().length);
        assertThat(values.get(CalcParamKey.TAX_RATE_PCT)).isEqualByComparingTo("15.4");
        assertThatCode(() -> CalcParamLoader.validateRequiredKeys(values)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("비율은 0과 100까지 받고 범위를 벗어나면 거부한다")
    void checksPercentRange() {
        assertThat(CalcParamCsvParser.parse(toStream(HEADER + "TAX_RATE_PCT,0\n"))).hasSize(1);
        assertThat(CalcParamCsvParser.parse(toStream(HEADER + "TAX_RATE_PCT,100\n"))).hasSize(1);
        assertThatThrownBy(() -> CalcParamCsvParser.parse(toStream(HEADER + "TAX_RATE_PCT,100.1\n")))
                .hasMessage("line 2: value is out of range for TAX_RATE_PCT");
        assertThatThrownBy(() -> CalcParamCsvParser.parse(toStream(HEADER + "TAX_RATE_PCT,-1\n")))
                .isInstanceOf(ReferenceDataLoadException.class);
    }

    @Test
    @DisplayName("개월 수는 1 이상의 정수만 받는다")
    void checksMonthsRange() {
        assertThat(CalcParamCsvParser.parse(toStream(HEADER + "SCENARIO_OBSERVATION_MONTHS,1\n"))).hasSize(1);
        assertThatThrownBy(() -> CalcParamCsvParser.parse(toStream(HEADER + "SCENARIO_OBSERVATION_MONTHS,0\n")))
                .isInstanceOf(ReferenceDataLoadException.class);
        assertThatThrownBy(() -> CalcParamCsvParser.parse(toStream(HEADER + "SCENARIO_OBSERVATION_MONTHS,12.5\n")))
                .isInstanceOf(ReferenceDataLoadException.class);
    }

    @Test
    @DisplayName("모르는 항목과 중복 항목은 거부한다")
    void rejectsUnknownAndDuplicateKeys() {
        assertThatThrownBy(() -> CalcParamCsvParser.parse(toStream(HEADER + "ETF_FEE_PCT,0.1\n")))
                .hasMessage("line 2: unknown param_key");
        assertThatThrownBy(() -> CalcParamCsvParser.parse(toStream(HEADER + "TAX_RATE_PCT,15.4\nTAX_RATE_PCT,14\n")))
                .hasMessage("line 3: duplicate key TAX_RATE_PCT");
    }

    @Test
    @DisplayName("필수 항목이 빠지면 빠진 항목을 알려주며 거부한다")
    void rejectsMissingRequiredKeys() {
        Map<CalcParamKey, BigDecimal> values =
                CalcParamCsvParser.parse(toStream(ALL_KEYS.replace("TAX_RATE_PCT,15.4\n", "")));

        assertThatThrownBy(() -> CalcParamLoader.validateRequiredKeys(values))
                .isInstanceOf(ReferenceDataLoadException.class)
                .hasMessage("required param keys are missing: [TAX_RATE_PCT]");
    }

    @Test
    @DisplayName("나쁨 분위가 좋음 분위보다 낮지 않으면 거부한다")
    void rejectsInvertedPercentiles() {
        Map<CalcParamKey, BigDecimal> values = CalcParamCsvParser.parse(
                toStream(ALL_KEYS.replace("SCENARIO_LOW_PERCENTILE,10", "SCENARIO_LOW_PERCENTILE,90")));

        assertThatThrownBy(() -> CalcParamLoader.validateRequiredKeys(values))
                .isInstanceOf(ReferenceDataLoadException.class)
                .hasMessageContaining("must be below");
    }

    @Test
    @DisplayName("부트스트랩 경로 수는 1 이상의 정수만 받는다")
    void checksBootstrapPathCount() {
        assertThat(CalcParamCsvParser.parse(toStream(HEADER + "BOOTSTRAP_PATH_COUNT,10000\n"))).hasSize(1);
        assertThatThrownBy(() -> CalcParamCsvParser.parse(toStream(HEADER + "BOOTSTRAP_PATH_COUNT,0\n")))
                .hasMessage("line 2: value is out of range for BOOTSTRAP_PATH_COUNT");
        assertThatThrownBy(() -> CalcParamCsvParser.parse(toStream(HEADER + "BOOTSTRAP_PATH_COUNT,100.5\n")))
                .isInstanceOf(ReferenceDataLoadException.class);
    }

    @Test
    @DisplayName("부트스트랩 시드는 0 이상의 정수만 받는다")
    void checksBootstrapSeed() {
        assertThat(CalcParamCsvParser.parse(toStream(HEADER + "BOOTSTRAP_SEED,0\n"))).hasSize(1);
        assertThat(CalcParamCsvParser.parse(toStream(HEADER + "BOOTSTRAP_SEED,20261117\n"))).hasSize(1);
        assertThatThrownBy(() -> CalcParamCsvParser.parse(toStream(HEADER + "BOOTSTRAP_SEED,-1\n")))
                .hasMessage("line 2: value is out of range for BOOTSTRAP_SEED");
        assertThatThrownBy(() -> CalcParamCsvParser.parse(toStream(HEADER + "BOOTSTRAP_SEED,1.5\n")))
                .isInstanceOf(ReferenceDataLoadException.class);
    }
}
