package com.moeun.backend.refdata.application;

import java.time.LocalDate;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;

import com.moeun.backend.refdata.DataKind;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 서버가 뜰 때 적재할 기준 데이터 목록(application.yml의 moeun.refdata).
 *
 * @param seeds 적재할 파일들. 비어 있으면 아무것도 적재하지 않는다
 */
@Validated
@ConfigurationProperties(prefix = "moeun.refdata")
public record RefDataSeedProperties(@Valid List<Seed> seeds) {

    public RefDataSeedProperties {
        seeds = seeds == null ? List.of() : List.copyOf(seeds);
    }

    /**
     * 적재할 파일 하나.
     *
     * @param kind 데이터 종류
     * @param versionLabel 버전 이름
     * @param baseDate 데이터 기준일
     * @param source 출처
     * @param file CSV 위치(classpath: 또는 file:)
     * @param optional 파일이 없을 때 건너뛰어도 되는지. 레포에 올리지 않고 각자 받는 데이터(S&P500)에 쓴다
     */
    public record Seed(
            @NotNull DataKind kind,
            @NotBlank String versionLabel,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate baseDate,
            @NotBlank String source,
            @NotNull Resource file,
            @DefaultValue("false") boolean optional) {
    }
}
