package com.moeun.backend.calc.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.EtfProduct;

/** 리포트(#162)가 서버 설정(application.yml)에서 기준 데이터를 읽는 방법. 로컬 가격 파일은 쓰지 않는다. */
class LocalSeedDataTest {

    private static final ClassPathResource CONFIG = new ClassPathResource("application.yml");

    @Test
    @DisplayName("(예외) 가격 파일이 없는 종목은 실패하지 않고 '데이터 없음'으로 두되, 종목 정보는 설정 그대로 읽는다")
    void keepsProductInfoWhenPriceFileIsMissing(@TempDir Path empty) {
        Map<String, String> environment = Map.of(
                "MOEUN_SP500_FILE", "file:" + empty.resolve("none-sp500.csv"),
                "MOEUN_ETF_069500_FILE", "file:" + empty.resolve("none-069500.csv"),
                "MOEUN_ETF_133690_FILE", "file:" + empty.resolve("none-133690.csv"));

        LocalSeedData seeds = LocalSeedData.read(CONFIG, environment::get);

        assertThat(seeds.etfProducts()).extracting(ReportProduct::product).containsExactly(
                new EtfProduct("SP500", "S&P500 추종 ETF", new BigDecimal("15.4")),
                new EtfProduct("069500", "코스피200 추종 ETF", BigDecimal.ZERO),
                new EtfProduct("133690", "나스닥100 추종 ETF", new BigDecimal("15.4")));
        assertThat(seeds.etfProducts()).allMatch(product -> product.findSeries().isEmpty());
    }

    @Test
    @DisplayName("환경 변수로 준 가격 파일을 읽고(없는 종목만 '데이터 없음'), 계산 파라미터·기본금리는 서버와 같은 seed에서 읽는다")
    void readsGivenPriceFileAndSeedParams(@TempDir Path folder) throws Exception {
        Path sp500 = folder.resolve("sp500.csv");
        Files.writeString(sp500, "﻿# 테스트\nym,close_value\n2016-10,100\n2016-11,110\n");

        LocalSeedData seeds = LocalSeedData.read(CONFIG, environmentWith(folder, "MOEUN_SP500_FILE", sp500)::get);

        assertThat(seeds.etfProducts()).extracting(product -> product.findSeries().isPresent())
                .containsExactly(true, false, false);
        ReportProduct first = seeds.etfProducts().get(0);
        assertThat(first.findSeries()).hasValueSatisfying(series -> {
            assertThat(series.monthlyCloses()).extracting(close -> close.closeValue().toPlainString())
                    .containsExactly("100", "110");
            assertThat(series.version().baseDate()).hasToString("2026-10-31");
        });
        assertThat(seeds.calcParams().getValue(CalcParamKey.BOOTSTRAP_PATH_COUNT)).isEqualByComparingTo("10000");
        assertThat(seeds.calcParams().getValue(CalcParamKey.BOOTSTRAP_SEED)).isEqualByComparingTo("20261117");
        assertThat(seeds.savingsRatePct()).isEqualByComparingTo("2.84");
        assertThat(seeds.savingsRateBaseMonth()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(seeds.describeSavingsRate()).isEqualTo("2.84%(은행연합회 적금 평균금리, 2026-10 기준)");
    }

    @Test
    @DisplayName("(예외) 가격 파일에 빠진 달·거꾸로 된 순서·0 이하 종가·다른 머리글이 있으면 조용히 계산하지 않고 멈춘다")
    void rejectsMalformedPriceFile(@TempDir Path folder) throws Exception {
        Map<String, String> bodies = Map.of(
                "gap.csv", "ym,close_value\n2016-10,100\n2016-12,110\n",
                "reversed.csv", "ym,close_value\n2016-11,110\n2016-10,100\n",
                "zero.csv", "ym,close_value\n2016-10,100\n2016-11,0\n",
                "header.csv", "month,close\n2016-10,100\n2016-11,110\n");
        for (Map.Entry<String, String> body : bodies.entrySet()) {
            Path file = folder.resolve(body.getKey());
            Files.writeString(file, body.getValue());

            assertThatThrownBy(() -> LocalSeedData.read(CONFIG,
                    environmentWith(folder, "MOEUN_SP500_FILE", file)::get))
                    .as(body.getKey())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * 종목 세 개의 가격 파일 환경 변수. 지정한 하나만 주어진 파일이고 나머지는 없는 경로다. 평소 테스트가 개발자 로컬의
     * 가격 파일(backend/data/seed)을 읽지 않게 세 키를 모두 채운다.
     */
    private static Map<String, String> environmentWith(Path folder, String key, Path file) {
        Map<String, String> environment = new HashMap<>(Map.of(
                "MOEUN_SP500_FILE", "file:" + folder.resolve("none-sp500.csv"),
                "MOEUN_ETF_069500_FILE", "file:" + folder.resolve("none-069500.csv"),
                "MOEUN_ETF_133690_FILE", "file:" + folder.resolve("none-133690.csv")));
        environment.put(key, "file:" + file);
        return environment;
    }
}
