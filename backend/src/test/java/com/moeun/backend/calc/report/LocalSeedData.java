package com.moeun.backend.calc.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import com.moeun.backend.refdata.CalcParamKey;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionInfo;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.EtfSeries;

/**
 * 리포트(#162)가 쓸 기준 데이터를 서버와 같은 설정(application.yml의 {@code moeun.refdata.seeds})에서 읽는다.
 * 종목 목록·화면 이름·세율·기준일을 설정 한 곳에서 가져오므로 종목을 늘리면 리포트에도 바로 들어간다.
 * <p>ETF 가격 파일은 레포에 없어(TBD-08) 개발자 로컬 파일을 읽고, 없으면 그 종목을 "데이터 없음"으로 둔다.
 * DB를 거치지 않으므로 데이터 버전 id는 리포트용 임시 번호다.
 *
 * @param etfProducts 설정 순서대로의 ETF 종목
 * @param calcParams 계산 파라미터(seed/calc-param.csv)
 * @param savingsRatePct 적금 기본금리(seed/savings-avg-rate.csv)
 * @param savingsRateBaseMonth 기본금리 기준월
 */
record LocalSeedData(List<ReportProduct> etfProducts, CalcParams calcParams, BigDecimal savingsRatePct,
        YearMonth savingsRateBaseMonth) {

    private static final String SEEDS = "moeun.refdata.seeds";
    private static final String PROFILE_KEY = "spring.config.activate.on-profile";
    /** {@code ${MOEUN_SP500_FILE:file:data/seed/sp500-monthly.csv}} 모양의 자리표시자. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([A-Za-z0-9_]+):(.*)}$");
    private static final String FILE_PREFIX = "file:";
    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String BOM = "﻿";
    private static final String ETF_HEADER = "ym,close_value";
    private static final String CALC_PARAM_HEADER = "param_key,param_value";
    private static final String SAVINGS_RATE_HEADER = "base_month,avg_rate_pct";

    /** 서버 설정과 실제 환경 변수로 읽는다. 환경 변수가 없으면 설정의 기본 경로(backend/data/seed)다. */
    static LocalSeedData read() {
        return read(new ClassPathResource("application.yml"), System::getenv);
    }

    /**
     * @param config application.yml
     * @param environment 환경 변수 조회(없으면 null)
     */
    static LocalSeedData read(Resource config, UnaryOperator<String> environment) {
        Properties properties = loadYaml(config);
        List<ReportProduct> products = new ArrayList<>();
        CalcParams params = null;
        BigDecimal savingsRate = null;
        YearMonth savingsBaseMonth = null;
        for (int index = 0; properties.containsKey(buildSeedKey(index, "kind")); index++) {
            DataKind kind = DataKind.valueOf(properties.getProperty(buildSeedKey(index, "kind")));
            String location = resolve(properties.getProperty(buildSeedKey(index, "file")), environment);
            LocalDate baseDate = LocalDate.parse(properties.getProperty(buildSeedKey(index, "base-date")));
            String label = properties.getProperty(buildSeedKey(index, "version-label"));
            switch (kind) {
                case ETF_PRICE -> products.add(readEtf(properties, index, location, baseDate, label));
                case CALC_PARAM -> params = readCalcParams(location, baseDate, label);
                case SAVINGS_RATE -> {
                    List<String[]> rows = readRows(location, SAVINGS_RATE_HEADER);
                    String[] latest = rows.get(rows.size() - 1);
                    savingsBaseMonth = YearMonth.parse(latest[0]);
                    savingsRate = new BigDecimal(latest[1]);
                }
                default -> throw new IllegalStateException("unknown seed kind " + kind);
            }
        }
        if (params == null || savingsRate == null) {
            throw new IllegalStateException("calc params and savings rate seeds are required");
        }
        return new LocalSeedData(List.copyOf(products), params, savingsRate, savingsBaseMonth);
    }

    /** 리포트 머리말의 적금 금리 설명. */
    String describeSavingsRate() {
        return ReportFormat.formatPercentValue(savingsRatePct) + "(은행연합회 적금 평균금리, " + savingsRateBaseMonth + " 기준)";
    }

    private static ReportProduct readEtf(Properties properties, int index, String location, LocalDate baseDate,
            String label) {
        EtfProduct product = new EtfProduct(properties.getProperty(buildSeedKey(index, "product-code")),
                properties.getProperty(buildSeedKey(index, "display-name")),
                new BigDecimal(properties.getProperty(buildSeedKey(index, "gain-tax-rate-pct"))));
        if (!isReadable(location)) {
            return ReportProduct.missing(product);
        }
        DataVersionInfo version = new DataVersionInfo(100L + index, DataKind.ETF_PRICE, label, baseDate,
                "리포트용 로컬 파일", product.code());
        List<EtfSeries.MonthlyClose> closes = new ArrayList<>();
        for (String[] row : readRows(location, ETF_HEADER)) {
            YearMonth month = YearMonth.parse(row[0]);
            BigDecimal close = new BigDecimal(row[1]);
            // 서버 적재(EtfPriceCsvParser)가 거절할 파일로 조용히 계산하지 않게 같은 기본 검사를 한다
            if (close.signum() <= 0) {
                throw new IllegalStateException("close must be positive at " + month + " in " + location);
            }
            if (!closes.isEmpty() && !closes.get(closes.size() - 1).month().plusMonths(1).equals(month)) {
                throw new IllegalStateException("months must be ascending without gaps at " + month + " in "
                        + location);
            }
            closes.add(new EtfSeries.MonthlyClose(month, close));
        }
        return ReportProduct.of(new EtfSeries(version, product, closes));
    }

    private static CalcParams readCalcParams(String location, LocalDate baseDate, String label) {
        Map<CalcParamKey, BigDecimal> values = new EnumMap<>(CalcParamKey.class);
        for (String[] row : readRows(location, CALC_PARAM_HEADER)) {
            values.put(CalcParamKey.valueOf(row[0]), new BigDecimal(row[1]));
        }
        return new CalcParams(new DataVersionInfo(1L, DataKind.CALC_PARAM, label, baseDate, "seed/calc-param.csv"),
                values);
    }

    private static String buildSeedKey(int index, String field) {
        return SEEDS + "[" + index + "]." + field;
    }

    /**
     * 서버와 같은 YAML 로더로 읽는다(날짜 같은 값도 글자 그대로). 프로필 전용 문서(--- 아래 local 등)는 빼고
     * 기본 문서만 쓴다. 서버를 프로필 없이 띄운 것과 같다.
     */
    private static Properties loadYaml(Resource config) {
        List<PropertySource<?>> documents;
        try {
            documents = new YamlPropertySourceLoader().load(config.getFilename(), config);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read " + config, exception);
        }
        Properties properties = new Properties();
        for (PropertySource<?> document : documents) {
            if (document.containsProperty(PROFILE_KEY)
                    || !(document instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                properties.setProperty(name, String.valueOf(enumerable.getProperty(name)));
            }
        }
        return properties;
    }

    /**
     * 자리표시자면 환경 변수, 없거나 비어 있으면 기본값. 지금 설정에서는 서버와 같은 파일을 고른다.
     * 다만 서버와 달리 {@code backend/.env}(spring.config.import)와 {@code -D} 시스템 속성은 보지 않는다.
     */
    private static String resolve(String location, UnaryOperator<String> environment) {
        Matcher matcher = PLACEHOLDER.matcher(location);
        if (!matcher.matches()) {
            return location;
        }
        String value = environment.apply(matcher.group(1));
        return value == null || value.isBlank() ? matcher.group(2) : value;
    }

    private static boolean isReadable(String location) {
        if (location.startsWith(CLASSPATH_PREFIX)) {
            return new ClassPathResource(location.substring(CLASSPATH_PREFIX.length())).exists();
        }
        return Files.isRegularFile(toPath(location));
    }

    /** 주석(#)·빈 줄·머리글을 뺀 CSV 행. 첫 줄의 BOM은 지우고, 머리글이 기대와 다르면 멈춘다. */
    private static List<String[]> readRows(String location, String expectedHeader) {
        String text;
        try (InputStream in = open(location)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read " + location, exception);
        }
        if (text.startsWith(BOM)) {
            text = text.substring(BOM.length());
        }
        List<String[]> rows = new ArrayList<>();
        boolean headerChecked = false;
        for (String line : text.split("\n")) {
            String row = line.strip();
            if (row.isEmpty() || row.startsWith("#")) {
                continue;
            }
            if (!headerChecked) {
                if (!row.equals(expectedHeader)) {
                    throw new IllegalStateException("header must be " + expectedHeader + " in " + location);
                }
                headerChecked = true;
                continue;
            }
            rows.add(row.split(","));
        }
        return rows;
    }

    private static InputStream open(String location) throws IOException {
        if (location.startsWith(CLASSPATH_PREFIX)) {
            return new ClassPathResource(location.substring(CLASSPATH_PREFIX.length())).getInputStream();
        }
        return Files.newInputStream(toPath(location));
    }

    private static Path toPath(String location) {
        return Path.of(location.startsWith(FILE_PREFIX) ? location.substring(FILE_PREFIX.length()) : location);
    }
}
