package com.moeun.backend.refdata.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.TestPropertySource;

import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionService;

// TestcontainersConfig가 config 패키지에 있어서, config 모듈을 함께 포함하지 않으면 DB 컨테이너 빈이 걸러진다
@ApplicationModuleTest(extraIncludes = {"config", "auth"})
@Import(TestcontainersConfig.class)
@TestPropertySource(properties = {
    "moeun.refdata.seeds[0].kind=SP500",
    "moeun.refdata.seeds[0].version-label=test-2025-12",
    "moeun.refdata.seeds[0].base-date=2025-12-31",
    "moeun.refdata.seeds[0].source=테스트용 가짜 데이터",
    "moeun.refdata.seeds[0].file=classpath:refdata/test-sp500-monthly.csv",
    "moeun.refdata.seeds[1].kind=SAVINGS_RATE",
    "moeun.refdata.seeds[1].version-label=test-2025-12",
    "moeun.refdata.seeds[1].base-date=2025-12-01",
    "moeun.refdata.seeds[1].source=테스트용 가짜 데이터",
    "moeun.refdata.seeds[1].file=classpath:refdata/test-savings-avg-rate.csv",
    "moeun.refdata.seeds[2].kind=CALC_PARAM",
    "moeun.refdata.seeds[2].version-label=test-v1",
    "moeun.refdata.seeds[2].base-date=2025-12-01",
    "moeun.refdata.seeds[2].source=테스트용 임시값",
    "moeun.refdata.seeds[2].file=classpath:refdata/test-calc-param.csv"
})
class ReferenceDataSeederTest {

    @Autowired
    ReferenceDataSeeder seeder;

    @Autowired
    DataVersionService dataVersionService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("서버가 뜨면 설정에 적힌 세 종류가 모두 적재돼 활성 버전이 된다")
    void loadsSeedsOnStartup() {
        assertThat(dataVersionService.findActive(DataKind.SP500)).isPresent();
        assertThat(dataVersionService.findActive(DataKind.SAVINGS_RATE)).isPresent();
        assertThat(dataVersionService.findActive(DataKind.CALC_PARAM)).isPresent();
        assertThat(countVersions()).isEqualTo(3);
    }

    @Test
    @DisplayName("다시 실행해도 같은 버전을 중복으로 넣지 않는다")
    void doesNotLoadTwice() {
        seeder.run(new DefaultApplicationArguments());

        assertThat(countVersions()).isEqualTo(3);
    }

    private int countVersions() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM data_version", Integer.class);
    }
}
