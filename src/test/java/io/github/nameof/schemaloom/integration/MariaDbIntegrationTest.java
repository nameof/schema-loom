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

/** 使用官方驱动验证本地 Podman MariaDB，测试结束清理所有测试对象。 */
public class MariaDbIntegrationTest {
    @Test
    public void migratesTablesMetadataDefaultsAndView() {
        String host = System.getenv("SCHEMALOOM_IT_MARIADB_HOST");
        Assume.assumeTrue("配置 MariaDB 主机后执行真实集成测试", StrUtil.isNotBlank(host));
        String database = required("DATABASE");
        String user = required("USER");
        String password = required("PASSWORD");
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.MARIADB, host,
                Integer.parseInt(StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_MARIADB_PORT"), "3306")),
                database, user, password, StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_MARIADB_DRIVER_ID"), "mariadb114"), null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider setup = loader.connect(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(setup.getConnection(), true));
            clean(jdbc);
            try {
                jdbc.execute("CREATE TABLE sl_mariadb_source (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(40) NOT NULL DEFAULT 'fallback' COMMENT '字段注释', amount DECIMAL(12,2) DEFAULT 0.00, derived DECIMAL(12,2) GENERATED ALWAYS AS (amount + 1) STORED) COMMENT='表注释'");
                jdbc.execute("CREATE UNIQUE INDEX sl_mariadb_name_uq ON sl_mariadb_source(name)");
                jdbc.execute("INSERT INTO sl_mariadb_source (name, amount) VALUES ('中文', 12.34), ('beta', 20.75)");
                jdbc.execute("CREATE VIEW sl_mariadb_source_view AS SELECT id, name FROM sl_mariadb_source");
                DatabaseMetadataService service = new DatabaseMetadataService();
                assertEquals("MariaDB", service.getDatabaseInfo(setup).getProductName());
                MetadataQuery query = MetadataQuery.fromConnectionInfo(config, "sl_mariadb_source");
                assertEquals(database, query.getCatalog());
                assertNull(query.getSchema());
                assertEquals(1, service.listTables(setup, query).size());
                TableInfo source = service.getTable(setup, config.table("sl_mariadb_source"));
                assertEquals(database, source.getName().getCatalog());
                assertNull(source.getName().getSchema());
                assertNotNull(source.getPrimaryKey());
                assertEquals("id", source.getPrimaryKey().getColumns().get(0));
                assertTrue(source.getColumns().get(0).isAutoIncremented());
                assertEquals("字段注释", source.getColumns().get(1).getRemarks());
                assertEquals(Integer.valueOf(12), source.getColumns().get(2).getPrecision());
                assertEquals(Integer.valueOf(2), source.getColumns().get(2).getScale());
                assertTrue(source.getColumns().get(3).isGenerated());
                assertTrue(StrUtil.isNotBlank(source.getColumns().get(3).getGeneratedExpression()));
                assertEquals("表注释", source.getRemarks());
                assertTrue(source.getIndexes().stream().anyMatch(i -> i.isUnique() && "sl_mariadb_name_uq".equalsIgnoreCase(i.getName())));
                assertNotNull(service.getTableStatistics(setup, source.getName()));
                assertTrue(service.getSchemaStatistics(setup, new SchemaInfo(database, null)).getTotalTables() > 0);

                jdbc.execute("CREATE TABLE sl_mariadb_target (id INT)");
                jdbc.execute("INSERT INTO sl_mariadb_target VALUES (99)");
                EtlResult replaced = migrate(config, loader, TargetMode.REPLACE);
                assertSuccess(replaced);
                assertEquals(2, replaced.getRead());
                assertEquals(2, replaced.getWritten());
                List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, name, amount, derived FROM sl_mariadb_target ORDER BY id");
                assertEquals(2, rows.size());
                assertEquals("中文", rows.get(0).get("name"));
                assertEquals(new BigDecimal("12.34"), rows.get(0).get("amount"));
                assertEquals(new BigDecimal("13.34"), rows.get(0).get("derived"));
                assertEquals(new BigDecimal("20.75"), rows.get(1).get("amount"));
                assertEquals(new BigDecimal("21.75"), rows.get(1).get("derived"));
                TableInfo target = service.getTable(setup, config.table("sl_mariadb_target"));
                assertNotNull(target.getPrimaryKey());
                assertTrue(target.getColumns().get(0).isAutoIncremented());
                assertTrue(target.getColumns().get(3).isGenerated());
                assertEquals("表注释", target.getRemarks());
                assertTrue(target.getIndexes().stream().anyMatch(i -> i.isUnique() && "sl_mariadb_name_uq".equalsIgnoreCase(i.getName())));
                assertTrue(service.listTables(setup, MetadataQuery.fromConnectionInfo(config, "sl_mariadb_target_tmp")).isEmpty());
                jdbc.execute("DELETE FROM sl_mariadb_target");
                EtlResult appended = migrate(config, loader, TargetMode.APPEND);
                assertSuccess(appended);
                assertEquals(2, appended.getWritten());
                assertEquals(Integer.valueOf(2), jdbc.queryForObject("SELECT COUNT(*) FROM sl_mariadb_target", Integer.class));
                jdbc.execute("INSERT INTO sl_mariadb_target () VALUES ()");
                assertEquals("fallback", jdbc.queryForObject("SELECT name FROM sl_mariadb_target WHERE name = 'fallback'", String.class));
                assertEquals(new BigDecimal("1.00"), jdbc.queryForObject("SELECT derived FROM sl_mariadb_target WHERE name = 'fallback'", BigDecimal.class));
                assertSuccess(new JdbcViewMigrationTask(config, config, "sl_mariadb_source_view", "sl_mariadb_target_view", loader).run());
                assertEquals(Integer.valueOf(2), jdbc.queryForObject("SELECT COUNT(*) FROM sl_mariadb_target_view", Integer.class));
            } finally {
                clean(jdbc);
            }
        }
    }

    private EtlResult migrate(DatabaseConnectionInfo config, JdbcDriverLoader loader, TargetMode mode) {
        return EtlTask.builder().source(new JdbcTableSource(config, "sl_mariadb_source", loader, 1))
                .target(new JdbcTableTarget(config, "sl_mariadb_target", loader, MetadataErrorPolicy.FAIL))
                .targetMode(mode).build().run();
    }

    private void assertSuccess(EtlResult result) {
        String message = result.getErrors().isEmpty() ? "" : result.getErrors().get(0).getStage() + ": " + result.getErrors().get(0).getMessage();
        assertEquals(message, EtlStatus.SUCCESS, result.getStatus());
    }

    private String required(String suffix) {
        String value = System.getenv("SCHEMALOOM_IT_MARIADB_" + suffix);
        if (StrUtil.isBlank(value)) throw new IllegalArgumentException("缺少 MariaDB 测试配置：" + suffix);
        return value;
    }

    private void clean(JdbcTemplate jdbc) {
        jdbc.execute("DROP VIEW IF EXISTS sl_mariadb_target_view");
        jdbc.execute("DROP VIEW IF EXISTS sl_mariadb_source_view");
        jdbc.execute("DROP TABLE IF EXISTS sl_mariadb_target_tmp");
        jdbc.execute("DROP TABLE IF EXISTS sl_mariadb_target");
        jdbc.execute("DROP TABLE IF EXISTS sl_mariadb_source");
    }
}
