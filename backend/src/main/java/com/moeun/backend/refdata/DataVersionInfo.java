package com.moeun.backend.refdata;

import java.time.LocalDate;

/**
 * 기준 데이터 버전 한 건. 가정 보기에 기준일과 출처를 보여주고 calc_run에 id를 남기는 데 쓴다.
 *
 * @param id 버전 id
 * @param dataKind 데이터 종류
 * @param versionLabel 사람이 읽는 버전 이름. 종류 안에서 겹치지 않는다
 * @param baseDate 데이터 기준일
 * @param source 출처
 * @param productCode ETF 가격 버전의 종목 코드. 그 밖의 종류는 null
 */
public record DataVersionInfo(long id, DataKind dataKind, String versionLabel, LocalDate baseDate, String source,
        String productCode) {

    /** 종목이 없는 종류(금리, 계산 파라미터)의 버전. */
    public DataVersionInfo(long id, DataKind dataKind, String versionLabel, LocalDate baseDate, String source) {
        this(id, dataKind, versionLabel, baseDate, source, null);
    }
}
