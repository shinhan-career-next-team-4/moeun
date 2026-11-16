package com.moeun.backend.refdata.application;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 적재할 버전의 표지. 데이터 파일에는 값만 두고, 이름·기준일·출처는 여기로 받는다.
 *
 * @param versionLabel 버전 이름. 같은 종류 안에서 이미 있으면 적재를 건너뛴다
 * @param baseDate 데이터 기준일
 * @param source 출처
 */
public record VersionSpec(String versionLabel, LocalDate baseDate, String source) {

    private static final int MAX_LABEL_LENGTH = 50;
    private static final int MAX_SOURCE_LENGTH = 100;

    public VersionSpec {
        Objects.requireNonNull(baseDate, "baseDate");
        requireText(versionLabel, MAX_LABEL_LENGTH, "versionLabel");
        requireText(source, MAX_SOURCE_LENGTH, "source");
    }

    private static void requireText(String text, int maxLength, String name) {
        if (text == null || text.isBlank() || text.length() > maxLength) {
            throw new ReferenceDataLoadException(name + " must be 1 to " + maxLength + " characters");
        }
    }
}
