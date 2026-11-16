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
 */
public record DataVersionInfo(long id, DataKind dataKind, String versionLabel, LocalDate baseDate, String source) {
}
