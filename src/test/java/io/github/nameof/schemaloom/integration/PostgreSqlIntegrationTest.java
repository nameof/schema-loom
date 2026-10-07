package io.github.nameof.schemaloom.integration;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.engine.EtlTask;
import io.github.nameof.schemaloom.metadata.*;
import io.github.nameof.schemaloom.migration.JdbcViewMigrationTask;
import io.github.nameof.schemaloom.source.JdbcTableSource;
import io.github.nameof.schemaloom.target.JdbcTableTarget;
import org.junit.Assume;
import org.junit.Test;

import java.sql.*;

import static org.junit.Assert.*;

/** 使用 Podman 启动的真实 PostgreSQL 验证连接、ETL、元数据、统计和视图迁移。 */
public class PostgreSqlIntegrationTest {
    @Test
    public void migratesTableReadsStatisticsAndCopiesView() throws Exception {
        String host = System.getenv("SCHEMALOOM_IT_POSTGRESQL_HOST");
        String database = System.getenv("SCHEMALOOM_IT_POSTGRESQL_DATABASE");
        String user = System.getenv("SCHEMALOOM_IT_POSTGRESQL_USER");
        String password = System.getenv("SCHEMALOOM_IT_POSTGRESQL_PASSWORD");
        String driverId = System.getenv("SCHEMALOOM_IT_POSTGRESQL_DRIVER_ID");
        Assume.assumeTrue("set PostgreSQL connection variables to run integration test", host != null && database != null && user != null && password != null);
        int port = Integer.parseInt(valueOrDefault("SCHEMALOOM_IT_POSTGRESQL_PORT", "5432"));
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.POSTGRESQL, host, port, database, user, password, driverId, null);
        JdbcDriverLoader loader = new JdbcDriverLoader();
        ConnectionProvider setup = loader.connect(config);
        try {
            Statement st = setup.getConnection().createStatement();
            st.execute("DROP VIEW IF EXISTS schemaloom_target_view");
            st.execute("DROP VIEW IF EXISTS schemaloom_source_view");
            st.execute("DROP TABLE IF EXISTS schemaloom_target");
            st.execute("DROP TABLE IF EXISTS schemaloom_target_tmp");
            st.execute("DROP TABLE IF EXISTS schemaloom_source");
            st.execute("CREATE TABLE schemaloom_source (id INTEGER PRIMARY KEY, name VARCHAR(100), amount NUMERIC(12,2))");
            st.execute("COMMENT ON TABLE schemaloom_source IS 'source table'");
            st.execute("CREATE INDEX schemaloom_source_name_idx ON schemaloom_source(name)");
            st.execute("INSERT INTO schemaloom_source VALUES (1, 'alpha', 10.50), (2, 'beta', 20.75), (3, 'gamma', 0.00)");
            st.execute("CREATE VIEW schemaloom_source_view AS SELECT id, name FROM schemaloom_source");
            st.execute("CREATE TABLE schemaloom_target (id INTEGER PRIMARY KEY, name VARCHAR(100), amount NUMERIC(12,2))");
            st.execute("INSERT INTO schemaloom_target VALUES (99, 'old', 99.99)");
            st.close();
            TableInfo metadata = new DatabaseMetadataService().getTable(setup, config.table("schemaloom_source"));
            assertEquals(3, metadata.getColumns().size());
            assertEquals("public", metadata.getName().getSchema());
            assertEquals(LogicalType.DECIMAL, metadata.getColumns().get(2).getLogicalType());
            assertEquals(Integer.valueOf(12), metadata.getColumns().get(2).getPrecision());
            assertEquals(Integer.valueOf(2), metadata.getColumns().get(2).getScale());
            assertNotNull(metadata.getPrimaryKey());
            assertTrue(new DatabaseMetadataService().getTableStatistics(setup, config.table("schemaloom_source")).getDataLength() > 0);
            assertTrue(new DatabaseMetadataService().getSchemaStatistics(setup, new SchemaInfo(null, "public")).getTotalTables() > 0);
        } finally {
            setup.close();
        }

        EtlResult result = EtlTask.builder().source(new JdbcTableSource(config, "schemaloom_source", loader, 2))
                .target(new JdbcTableTarget(config, "schemaloom_target", loader)).targetMode(TargetMode.REPLACE).build().run();
        assertEquals(EtlStatus.SUCCESS, result.getStatus());
        assertEquals(3, result.getWritten());

        EtlResult view = new JdbcViewMigrationTask(config, config, "schemaloom_source_view", "schemaloom_target_view", loader).run();
        assertEquals(EtlStatus.SUCCESS, view.getStatus());
        ConnectionProvider verify = loader.connect(config);
        try {
            ResultSet rs = verify.getConnection().createStatement().executeQuery("SELECT COUNT(*) FROM \"public\".\"schemaloom_target\"");
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
            assertTrue(new DatabaseMetadataService().listTables(verify,
                    new MetadataQuery(database, "public", "schemaloom_target_tmp")).isEmpty());
            ResultSet rows = verify.getConnection().createStatement().executeQuery("SELECT id, amount FROM public.schemaloom_target ORDER BY id");
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertEquals(new java.math.BigDecimal("10.50"), rows.getBigDecimal(2));
            rows.close();
            rs.close();
        } finally {
            verify.close();
            loader.close();
        }
    }

    private String valueOrDefault(String name, String value) {
        String actual = System.getenv(name);
        return actual == null || actual.trim().isEmpty() ? value : actual;
    }
}
