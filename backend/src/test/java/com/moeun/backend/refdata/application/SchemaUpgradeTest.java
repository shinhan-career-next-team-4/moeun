package com.moeun.backend.refdata.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.modulith.test.ApplicationModuleTest;

import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.EtfSeries;
import com.moeun.backend.refdata.ReferenceDataService;

/**
 * 기존 로컬 DB 보정(#138). #138 전 구조(data_kind SP500, sp500_monthly)인 DB에 새 schema.sql을 실행하면
 * 서버를 다시 만들지 않아도 ETF 종목 구조로 바뀌고, 옛 버전 id 그대로 다시 계산할 수 있어야 한다.
 */
@ApplicationModuleTest(extraIncludes = {"config", "auth"})
@Import(TestcontainersConfig.class)
class SchemaUpgradeTest {

    private static final ClassPathResource SCHEMA = new ClassPathResource("db/schema.sql");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    ReferenceDataService referenceDataService;

    @BeforeEach
    void makeOldStructure() {
        jdbc.execute("TRUNCATE data_version RESTART IDENTITY CASCADE");
        // #157 전 calc_run: changed 쪽 ETF 버전 칸 없음
        jdbc.execute("ALTER TABLE calc_run DROP COLUMN IF EXISTS changed_etf_version_id");
        // #138 전 data_version: product_code 없음, 종류에 SP500, 종류별 활성 1개, (종류, 이름) 유일
        jdbc.execute("DROP INDEX IF EXISTS uq_data_version_active_product");
        jdbc.execute("ALTER TABLE data_version DROP CONSTRAINT IF EXISTS ck_data_kind");
        jdbc.execute("ALTER TABLE data_version DROP CONSTRAINT IF EXISTS ck_data_version_product");
        jdbc.execute("ALTER TABLE data_version DROP CONSTRAINT IF EXISTS uq_data_version_label");
        jdbc.execute("ALTER TABLE data_version DROP COLUMN IF EXISTS product_code");
        jdbc.execute("ALTER TABLE data_version ADD CONSTRAINT ck_data_kind "
                + "CHECK (data_kind IN ('SAVINGS_RATE','SP500','CALC_PARAM'))");
        jdbc.execute("ALTER TABLE data_version ADD CONSTRAINT uq_data_version_label UNIQUE (data_kind, version_label)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_data_version_active ON data_version (data_kind) "
                + "WHERE is_active");
        jdbc.update("INSERT INTO data_version (data_kind, version_label, base_date, source, is_active) "
                + "VALUES ('SP500', 'investing-2016-10-2026-10', '2026-10-31', '옛 S&P500', true)");
        jdbc.update("INSERT INTO data_version (data_kind, version_label, base_date, source, is_active) "
                + "VALUES ('SAVINGS_RATE', 'kfb-2026-10', '2026-10-01', '옛 금리', true)");
        jdbc.update("INSERT INTO sp500_monthly (data_version_id, ym, close_value) "
                + "SELECT 1, make_date(2016, 10, 1) + (n || ' month')::interval, 1000 + n "
                + "FROM generate_series(0, 120) AS n");
    }

    @AfterEach
    void restoreNewStructure() {
        runSchema();
        jdbc.execute("TRUNCATE data_version RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE sp500_monthly");
    }

    private void runSchema() {
        new ResourceDatabasePopulator(SCHEMA).execute(dataSource);
    }

    @Test
    @DisplayName("옛 S&P500 버전이 같은 id 그대로 ETF 종목 SP500이 되고, 종가 121개와 종목 정보(세율 15.4%)를 돌려준다")
    void upgradesOldSp500Version() {
        runSchema();

        assertThat(jdbc.queryForObject("SELECT data_kind || '/' || product_code FROM data_version WHERE id = 1",
                String.class)).isEqualTo("ETF_PRICE/SP500");
        EtfSeries series = referenceDataService.requireEtfSeries(1L);
        assertThat(series.monthlyCloses()).hasSize(121);
        assertThat(series.product()).isEqualTo(new EtfProduct("SP500", "S&P500 추종 ETF", new BigDecimal("15.4")));
        assertThat(referenceDataService.findRepresentativeEtfSeries().orElseThrow().version().id()).isEqualTo(1L);
        assertThat(referenceDataService.findActiveSavingsAvgRate()).isEmpty();   // 금리 행은 이 테스트에서 넣지 않음
        assertThat(jdbc.queryForObject("SELECT product_code IS NULL FROM data_version WHERE id = 2", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name = 'calc_run' AND column_name = 'changed_etf_version_id'", Integer.class))
                .isEqualTo(1);
        // 앱 기동 때와 여기서 스키마가 두 번 돌아도 data_version을 가리키는 FK는 하나다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.key_column_usage k "
                + "JOIN information_schema.referential_constraints r ON r.constraint_name = k.constraint_name "
                + "WHERE k.table_name = 'calc_run' AND k.column_name = 'changed_etf_version_id'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("보정은 몇 번을 실행해도 결과가 같다(서버가 뜰 때마다 실행됨)")
    void upgradesIdempotently() {
        runSchema();
        runSchema();
        runSchema();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM etf_monthly WHERE data_version_id = 1", Integer.class))
                .isEqualTo(121);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM etf_product_version", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data_version WHERE data_kind = 'SP500'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("보정 뒤에는 종목마다 활성 버전을 하나씩 둘 수 있고, 같은 종목에 활성 버전 두 개는 막힌다")
    void allowsOneActiveVersionPerProduct() {
        runSchema();

        jdbc.update("INSERT INTO data_version (data_kind, product_code, version_label, base_date, source, is_active) "
                + "VALUES ('ETF_PRICE', '069500', 'investing-2016-10-2026-10', '2026-10-31', '새 종목', true)");
        Integer activeEtfVersions = jdbc.queryForObject(
                "SELECT count(*) FROM data_version WHERE data_kind = 'ETF_PRICE' AND is_active", Integer.class);
        assertThat(activeEtfVersions).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO data_version (data_kind, product_code, version_label, base_date, source, is_active) "
                        + "VALUES ('ETF_PRICE', 'SP500', 'other-label', '2026-10-31', '중복 활성', true)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
