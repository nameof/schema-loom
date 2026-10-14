package io.github.nameof.schemaloom.integration;

import cn.hutool.core.util.StrUtil;
import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.engine.EtlTask;
import io.github.nameof.schemaloom.metadata.*;
import io.github.nameof.schemaloom.migration.JdbcViewMigrationTask;
import io.github.nameof.schemaloom.source.JdbcTableSource;
import io.github.nameof.schemaloom.target.JdbcTableTarget;
import org.junit.Assume;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** 使用达梦容器验证本地 Podman 集成测试，测试结束清理所有测试对象。 */
public class DmIntegrationTest {
    @Test
    public void migratesTablesMetadataDefaultsAndView() {
        String host = System.getenv("SCHEMALOOM_IT_DM_HOST");
        Assume.assumeTrue("配置达梦主机后执行真实集成测试", StrUtil.isNotBlank(host));
        String database = required("DATABASE");
        String user = required("USER");
        String password = required("PASSWORD");
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.DM, host,
                Integer.parseInt(StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_DM_PORT"), "5236")),
                database, null, user, user, password,
                StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_DM_DRIVER_ID"), "dm"), null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider setup = loader.connect(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(setup.getConnection(), true));
            clean(jdbc);
            try {
                jdbc.execute("CREATE TABLE sl_dm_source ("
                        + "id INT IDENTITY(1,1) NOT NULL, "
                        + "name VARCHAR(40) NOT NULL DEFAULT 'fallback', "
                        + "amount DECIMAL(12,2) DEFAULT 0.00, "
                        + "derived DECIMAL(12,2) GENERATED ALWAYS AS (amount + 1), "
                        + "PRIMARY KEY (id))");
                jdbc.execute("COMMENT ON TABLE sl_dm_source IS '表注释'");
                jdbc.execute("COMMENT ON COLUMN sl_dm_source.name IS '字段注释'");
                jdbc.execute("CREATE UNIQUE INDEX sl_dm_name_uq ON sl_dm_source(name)");
                jdbc.execute("INSERT INTO sl_dm_source (name, amount) VALUES ('中文', 12.34)");
                jdbc.execute("INSERT INTO sl_dm_source (name, amount) VALUES ('beta', 20.75)");
                jdbc.execute("CREATE VIEW sl_dm_source_view AS SELECT id, name FROM sl_dm_source");

                DatabaseMetadataService service = new DatabaseMetadataService();
                assertTrue(service.getDatabaseInfo(setup).getProductName().contains("DM"));
                MetadataQuery query = MetadataQuery.fromConnectionInfo(config, "sl_dm_source");
                assertEquals(1, service.listTables(setup, query).size());
                TableInfo source = service.getTable(setup, config.table("sl_dm_source"));
                assertNotNull(source.getPrimaryKey());
                assertEquals("ID", source.getPrimaryKey().getColumns().get(0));
                assertTrue(source.getColumns().get(0).isAutoIncremented());
                assertEquals("字段注释", source.getColumns().get(1).getRemarks());
                assertEquals(Integer.valueOf(12), source.getColumns().get(2).getPrecision());
                assertEquals(Integer.valueOf(2), source.getColumns().get(2).getScale());
                assertTrue(source.getColumns().get(3).isGenerated());
                assertTrue(StrUtil.isNotBlank(source.getColumns().get(3).getGeneratedExpression()));
                assertEquals("表注释", source.getRemarks());
                assertTrue(source.getIndexes().stream().anyMatch(i -> i.isUnique() && "SL_DM_NAME_UQ".equalsIgnoreCase(i.getName())));
                assertNotNull(service.getTableStatistics(setup, source.getName()));

                jdbc.execute("CREATE TABLE sl_dm_target (id INT)");
                jdbc.execute("INSERT INTO sl_dm_target VALUES (99)");
                EtlResult replaced = migrate(config, loader, TargetMode.REPLACE);
                assertSuccess(replaced);
                assertEquals(2, replaced.getRead());
                assertEquals(2, replaced.getWritten());
                List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, name, amount, derived FROM sl_dm_target ORDER BY id");
                assertEquals(2, rows.size());
                assertEquals("中文", rows.get(0).get("NAME"));
                assertEquals(new BigDecimal("12.34"), rows.get(0).get("AMOUNT"));
                assertEquals(new BigDecimal("13.34"), rows.get(0).get("DERIVED"));
                assertEquals(new BigDecimal("20.75"), rows.get(1).get("AMOUNT"));
                assertEquals(new BigDecimal("21.75"), rows.get(1).get("DERIVED"));
                TableInfo target = service.getTable(setup, config.table("sl_dm_target"));
                assertNotNull(target.getPrimaryKey());
                assertTrue(target.getColumns().get(0).isAutoIncremented());
                assertTrue(target.getColumns().get(3).isGenerated());
                assertEquals("表注释", target.getRemarks());
                // 达梦索引名称是 Schema 级别的，迁移时添加 _TGT 后缀避免冲突
                assertTrue(target.getIndexes().stream().anyMatch(i -> i.isUnique() && "SL_DM_NAME_UQ_TGT".equalsIgnoreCase(i.getName())));
                assertTrue(service.listTables(setup, MetadataQuery.fromConnectionInfo(config, "sl_dm_target_tmp")).isEmpty());

                jdbc.execute("DELETE FROM sl_dm_target");
                EtlResult appended = migrate(config, loader, TargetMode.APPEND);
                assertSuccess(appended);
                assertEquals(2, appended.getWritten());
                assertEquals(Integer.valueOf(2), jdbc.queryForObject("SELECT COUNT(*) FROM sl_dm_target", Integer.class));

                assertSuccess(new JdbcViewMigrationTask(config, config, "sl_dm_source_view", "sl_dm_target_view", loader).run());
                assertEquals(Integer.valueOf(2), jdbc.queryForObject("SELECT COUNT(*) FROM sl_dm_target_view", Integer.class));
            } finally {
                clean(jdbc);
            }
        }
    }

    private EtlResult migrate(DatabaseConnectionInfo config, JdbcDriverLoader loader, TargetMode mode) {
        return EtlTask.builder().source(new JdbcTableSource(config, "sl_dm_source", loader, 1))
                .target(new JdbcTableTarget(config, "sl_dm_target", loader, MetadataErrorPolicy.FAIL))
                .targetMode(mode).build().run();
    }

    private void assertSuccess(EtlResult result) {
        if (result.getStatus() != EtlStatus.SUCCESS) {
            StringBuilder sb = new StringBuilder("ETL failed: ").append(result.getStatus());
            for (EtlError error : result.getErrors()) {
                sb.append("\n  stage=").append(error.getStage()).append(" type=").append(error.getType()).append(" message=").append(error.getMessage());
            }
            fail(sb.toString());
        }
    }

    private String required(String suffix) {
        String value = System.getenv("SCHEMALOOM_IT_DM_" + suffix);
        if (StrUtil.isBlank(value)) throw new IllegalArgumentException("缺少达梦测试配置：" + suffix);
        return value;
    }

    private void clean(JdbcTemplate jdbc) {
        try { jdbc.execute("DROP VIEW sl_dm_target_view"); } catch (Exception ignored) {}
        try { jdbc.execute("DROP VIEW sl_dm_source_view"); } catch (Exception ignored) {}
        jdbc.execute("DROP TABLE IF EXISTS sl_dm_target_tmp");
        jdbc.execute("DROP TABLE IF EXISTS sl_dm_target");
        jdbc.execute("DROP TABLE IF EXISTS sl_dm_source");
    }
}
