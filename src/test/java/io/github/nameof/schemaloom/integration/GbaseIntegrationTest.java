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

import java.math.BigDecimal;
import java.io.File;
import java.net.URLClassLoader;
import java.sql.*;
import java.util.Properties;
import static org.junit.Assert.*;

/** 使用 GBase 8a 容器验证本地 Podman 集成测试，测试结束清理所有测试对象。 */
public class GbaseIntegrationTest {
    @Test
    public void migratesTablesMetadataDefaultsAndView() throws Exception {
        String host = System.getenv("SCHEMALOOM_IT_GBASE_HOST");
        Assume.assumeTrue("配置 GBase 8a 主机后执行真实集成测试", StrUtil.isNotBlank(host));
        String database = required("DATABASE");
        String user = required("USER");
        String password = required("PASSWORD");
        int port = Integer.parseInt(StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_GBASE_PORT"), "5258"));
        String driverId = StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_GBASE_DRIVER_ID"), "gbase8a");
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.GBASE, host, port,
                database, null, null, user, password, driverId, null);
        // GBase 8a 驱动默认 autoCommit=false，通过 JdbcDriverLoader 代理设置不生效，
        // 使用独立的原生连接做 DDL setup 和数据验证
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             Connection rawConn = createRawConnection(config, port, database, user, password)) {
            rawConn.setAutoCommit(true);
            Statement st = rawConn.createStatement();
            clean(st);
            try {
                // GBase 8a 使用 AUTO_INCREMENT，生成列语法与 MySQL 一致
                st.execute("CREATE TABLE sl_gbase_source ("
                        + "id INT AUTO_INCREMENT PRIMARY KEY, "
                        + "name VARCHAR(40) NOT NULL DEFAULT 'fallback', "
                        + "amount DECIMAL(12,2) DEFAULT 0.00, "
                        + "derived DECIMAL(12,2) GENERATED ALWAYS AS (amount + 1) STORED)");
                st.execute("ALTER TABLE sl_gbase_source COMMENT = '表注释'");
                st.execute("INSERT INTO sl_gbase_source (name, amount) VALUES ('中文', 12.34)");
                st.execute("INSERT INTO sl_gbase_source (name, amount) VALUES ('beta', 20.75)");
                st.execute("CREATE VIEW sl_gbase_source_view AS SELECT id, name FROM sl_gbase_source");

                ConnectionProvider metaConn = loader.connect(config);
                try {
                    DatabaseMetadataService service = new DatabaseMetadataService();
                    MetadataQuery query = MetadataQuery.fromConnectionInfo(config, "sl_gbase_source");
                    assertEquals(1, service.listTables(metaConn, query).size());
                    TableInfo source = service.getTable(metaConn, config.table("sl_gbase_source"));
                    assertNotNull(source.getPrimaryKey());
                    assertEquals("id", source.getPrimaryKey().getColumns().get(0));
                    assertTrue(source.getColumns().get(0).isAutoIncremented());
                    assertEquals(Integer.valueOf(12), source.getColumns().get(2).getPrecision());
                    assertEquals(Integer.valueOf(2), source.getColumns().get(2).getScale());
                    assertTrue(source.getColumns().get(3).isGenerated());
                    assertTrue(StrUtil.isNotBlank(source.getColumns().get(3).getGeneratedExpression()));
                    assertEquals("表注释", source.getRemarks());
                    assertNotNull(service.getTableStatistics(metaConn, source.getName()));
                } finally {
                    metaConn.close();
                }

                st.execute("CREATE TABLE sl_gbase_target (id INT)");
                st.execute("INSERT INTO sl_gbase_target VALUES (99)");

                EtlResult replaced = migrate(config, loader, TargetMode.REPLACE);
                assertSuccess(replaced);
                assertEquals(2, replaced.getRead());
                assertEquals(2, replaced.getWritten());

                ResultSet rs = st.executeQuery("SELECT id, name, amount, derived FROM sl_gbase_target ORDER BY id");
                assertTrue(rs.next());
                assertEquals("中文", rs.getString("name"));
                assertEquals(new BigDecimal("12.34"), rs.getBigDecimal("amount"));
                assertEquals(new BigDecimal("13.34"), rs.getBigDecimal("derived"));
                assertTrue(rs.next());
                assertEquals(new BigDecimal("20.75"), rs.getBigDecimal("amount"));
                assertEquals(new BigDecimal("21.75"), rs.getBigDecimal("derived"));
                assertFalse(rs.next());
                rs.close();

                ConnectionProvider targetConn = loader.connect(config);
                try {
                    TableInfo target = new DatabaseMetadataService().getTable(targetConn, config.table("sl_gbase_target"));
                    assertNotNull(target.getPrimaryKey());
                    assertTrue(target.getColumns().get(0).isAutoIncremented());
                    assertEquals("表注释", target.getRemarks());
                    // REPLACE 模式完成后临时表应已被重命名为目标表
                    assertTrue(new DatabaseMetadataService().listTables(targetConn,
                            MetadataQuery.fromConnectionInfo(config, "sl_gbase_target_tmp")).isEmpty());
                } finally {
                    targetConn.close();
                }

                st.execute("DELETE FROM sl_gbase_target");
                EtlResult appended = migrate(config, loader, TargetMode.APPEND);
                assertSuccess(appended);
                assertEquals(2, appended.getWritten());

                ResultSet cnt = st.executeQuery("SELECT COUNT(*) FROM sl_gbase_target");
                assertTrue(cnt.next());
                assertEquals(2, cnt.getInt(1));
                cnt.close();

                assertSuccess(new JdbcViewMigrationTask(config, config, "sl_gbase_source_view", "sl_gbase_target_view", loader).run());

                ResultSet vrs = st.executeQuery("SELECT COUNT(*) FROM sl_gbase_target_view");
                assertTrue(vrs.next());
                assertEquals(2, vrs.getInt(1));
                vrs.close();
            } finally {
                clean(st);
                st.close();
            }
        }
    }

    private static Connection createRawConnection(DatabaseConnectionInfo config, int port, String database,
                                                   String user, String password) throws Exception {
        java.net.URL driverUrl = new File("drivers/gbase-connector-java-9.5.0.10-build1-bin.jar").toURI().toURL();
        URLClassLoader cl = new URLClassLoader(new java.net.URL[]{driverUrl}, GbaseIntegrationTest.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(cl);
        try {
            Class<?> driverClass = cl.loadClass("com.gbase.jdbc.Driver");
            Driver driver = (Driver) driverClass.newInstance();
            Properties props = new Properties();
            props.setProperty("user", user);
            props.setProperty("password", password);
            return driver.connect("jdbc:gbase://localhost:" + port + "/" + database, props);
        } finally {
            Thread.currentThread().setContextClassLoader(cl.getParent());
        }
    }

    private EtlResult migrate(DatabaseConnectionInfo config, JdbcDriverLoader loader, TargetMode mode) {
        return EtlTask.builder().source(new JdbcTableSource(config, "sl_gbase_source", loader, 1))
                .target(new JdbcTableTarget(config, "sl_gbase_target", loader, MetadataErrorPolicy.FAIL))
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
        String value = System.getenv("SCHEMALOOM_IT_GBASE_" + suffix);
        if (StrUtil.isBlank(value)) throw new IllegalArgumentException("缺少 GBase 8a 测试配置：" + suffix);
        return value;
    }

    private void clean(Statement st) {
        try { st.execute("DROP VIEW IF EXISTS sl_gbase_target_view"); } catch (Exception ignored) {}
        try { st.execute("DROP VIEW IF EXISTS sl_gbase_source_view"); } catch (Exception ignored) {}
        try { st.execute("DROP TABLE IF EXISTS sl_gbase_target_tmp"); } catch (Exception ignored) {}
        try { st.execute("DROP TABLE IF EXISTS sl_gbase_target"); } catch (Exception ignored) {}
        try { st.execute("DROP TABLE IF EXISTS sl_gbase_source"); } catch (Exception ignored) {}
    }
}
