package com.moeun.backend.refdata.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.moeun.backend.refdata.DataKind;

public interface DataVersionRepository extends JpaRepository<DataVersion, Long> {

    Optional<DataVersion> findByDataKindAndIsActiveTrue(DataKind dataKind);

    boolean existsByDataKindAndVersionLabel(DataKind dataKind, String versionLabel);
}
