package com.moeun.backend.refdata.domain;

import java.time.Instant;
import java.time.LocalDate;

import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionInfo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 기준 데이터를 한 번 적재한 기록. 적재 후 데이터는 바꾸지 않고 활성 여부만 바꾼다. */
@Entity
@Table(name = "data_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DataKind dataKind;

    @Column(nullable = false, length = 50)
    private String versionLabel;

    @Column(nullable = false)
    private LocalDate baseDate;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(nullable = false)
    private boolean isActive;

    // DB의 DEFAULT now()가 채운다
    @Column(nullable = false, insertable = false, updatable = false)
    private Instant loadedAt;

    private DataVersion(DataKind dataKind, String versionLabel, LocalDate baseDate, String source) {
        this.dataKind = dataKind;
        this.versionLabel = versionLabel;
        this.baseDate = baseDate;
        this.source = source;
        this.isActive = false;
    }

    /** 새 버전은 꺼진 채로 만든다. 데이터 행을 다 넣은 뒤에 켜야 반쯤 들어간 버전이 쓰이지 않는다. */
    public static DataVersion createInactive(DataKind dataKind, String versionLabel, LocalDate baseDate,
            String source) {
        return new DataVersion(dataKind, versionLabel, baseDate, source);
    }

    public void activate() {
        this.isActive = true;
    }

    public void deactivate() {
        this.isActive = false;
    }

    public DataVersionInfo toInfo() {
        return new DataVersionInfo(id, dataKind, versionLabel, baseDate, source);
    }
}
