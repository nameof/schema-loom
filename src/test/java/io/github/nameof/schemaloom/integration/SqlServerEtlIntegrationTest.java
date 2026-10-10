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

public class SqlServerEtlIntegrationTest {
    @Test
    public void readsIdentityAndComputedColumnFromRealDatabase() throws Exception {
        String password = System.getenv("SCHEMALOOM_IT_SQLSERVER_PASSWORD");
        Assume.assumeTrue("set SQL Server password to run integration test", password != null);
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.SQL_SERVER,
                value("SCHEMALOOM_IT_SQLSERVER_HOST", "localhost"), intValue("SCHEMALOOM_IT_SQLSERVER_PORT", 11433),
                value("SCHEMALOOM_IT_SQLSERVER_DATABASE", "master"), null, "dbo", "sa", password,
                value("SCHEMALOOM_IT_SQLSERVER_DRIVER_ID", "sqlserver2022"), null);
        JdbcDriverLoader loader = new JdbcDriverLoader();
        ConnectionProvider provider = loader.connect(config);
        try {
            Statement statement = provider.getConnection().createStatement();
            try {
                statement.execute("IF OBJECT_ID('dbo.schemaloom_boundary_target', 'U') IS NOT NULL DROP TABLE dbo.schemaloom_boundary_target");
                statement.execute("IF OBJECT_ID('dbo.schemaloom_boundary', 'U') IS NOT NULL DROP TABLE dbo.schemaloom_boundary");
                statement.execute("CREATE TABLE dbo.schemaloom_boundary (id INT IDENTITY(1,1) PRIMARY KEY, amount DECIMAL(10,2) NOT NULL, derived AS (amount + 1))");
                statement.execute("INSERT INTO dbo.schemaloom_boundary (amount) VALUES (12.34)");
            } finally {
                statement.close();
            }
            TableInfo table = new DatabaseMetadataService().getTable(provider, config.table("schemaloom_boundary"));
            assertTrue(table.getColumns().get(0).isAutoIncremented());
            assertTrue(table.getColumns().get(2).isGenerated());
            assertNotNull(table.getColumns().get(2).getGeneratedExpression());
            EtlResult migration = EtlTask.builder()
                    .source(new JdbcTableSource(config, "schemaloom_boundary", loader, 2))
                    .target(new JdbcTableTarget(config, "schemaloom_boundary_target", loader, MetadataErrorPolicy.FAIL))
                    .targetMode(TargetMode.REPLACE).build().run();
            assertEquals(migration.getErrors().isEmpty() ? "" : migration.getErrors().get(0).getMessage(),
                    EtlStatus.SUCCESS, migration.getStatus());
            ResultSet copied = provider.getConnection().createStatement()
                    .executeQuery("SELECT id, amount, derived FROM dbo.schemaloom_boundary_target");
            assertTrue(copied.next());
            assertEquals(new java.math.BigDecimal("12.34"), copied.getBigDecimal("amount"));
            assertEquals(new java.math.BigDecimal("13.34"), copied.getBigDecimal("derived"));
            copied.close();
        } finally {
            provider.close();
            loader.close();
        }
    }
    @Test
    public void migratesTableAndReadsMetadata() throws Exception {
        String password = System.getenv("SCHEMALOOM_IT_SQLSERVER_PASSWORD");
        Assume.assumeTrue("set SCHEMALOOM_IT_SQLSERVER_PASSWORD to run SQL Server integration test", password != null);
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.SQL_SERVER,
                value("SCHEMALOOM_IT_SQLSERVER_HOST", "localhost"), intValue("SCHEMALOOM_IT_SQLSERVER_PORT", 11433),
                value("SCHEMALOOM_IT_SQLSERVER_DATABASE", "master"), null, "dbo", "sa", password,
                value("SCHEMALOOM_IT_SQLSERVER_DRIVER_ID", "sqlserver2022"), null);
        JdbcDriverLoader loader = new JdbcDriverLoader();
        ConnectionProvider setup = loader.connect(config);
        try {
            Statement st = setup.getConnection().createStatement();
            st.executeUpdate("IF OBJECT_ID('dbo.schemaloom_target_view', 'V') IS NOT NULL DROP VIEW dbo.schemaloom_target_view");
            st.executeUpdate("IF OBJECT_ID('dbo.schemaloom_source_view', 'V') IS NOT NULL DROP VIEW dbo.schemaloom_source_view");
            st.executeUpdate("IF OBJECT_ID('dbo.schemaloom_target', 'U') IS NOT NULL DROP TABLE dbo.schemaloom_target");
            st.executeUpdate("IF OBJECT_ID('dbo.schemaloom_source', 'U') IS NOT NULL DROP TABLE dbo.schemaloom_source");
            st.executeUpdate("CREATE TABLE dbo.schemaloom_source (id INT PRIMARY KEY, name NVARCHAR(100), amount DECIMAL(12,2))");
            st.executeUpdate("INSERT INTO dbo.schemaloom_source VALUES (1, N'alpha', 10.50), (2, N'beta', 20.75)");
            st.executeUpdate("CREATE VIEW dbo.schemaloom_source_view AS SELECT id, name FROM dbo.schemaloom_source");
            st.close();
            TableInfo info = new DatabaseMetadataService().getTable(setup, config.table("schemaloom_source"));
            assertEquals(3, info.getColumns().size());
            assertEquals("master", info.getName().getCatalog());
            assertEquals("dbo", info.getName().getSchema());
            assertEquals(LogicalType.DECIMAL, info.getColumns().get(2).getLogicalType());
            assertEquals(Integer.valueOf(12), info.getColumns().get(2).getPrecision());
            assertEquals(Integer.valueOf(2), info.getColumns().get(2).getScale());
            assertNotNull(info.getPrimaryKey());
            assertTrue(new DatabaseMetadataService().getSchemaStatistics(setup, new SchemaInfo(null, "dbo")).getTotalTables() > 0);
            assertNotNull(new DatabaseMetadataService().getTableStatistics(setup, config.table("schemaloom_source")));
        } finally { setup.close(); }

        EtlResult result = EtlTask.builder().source(new JdbcTableSource(config, "schemaloom_source", loader, 2))
                .target(new JdbcTableTarget(config, "schemaloom_target", loader)).targetMode(TargetMode.APPEND).build().run();
        assertEquals(String.valueOf(result.getErrors()), EtlStatus.SUCCESS, result.getStatus());
        assertEquals(2, result.getWritten());
        EtlResult view = new JdbcViewMigrationTask(config, config, "schemaloom_source_view", "schemaloom_target_view", loader).run();
        assertEquals(view.getErrors().isEmpty() ? "" : view.getErrors().get(0).getMessage(), EtlStatus.SUCCESS, view.getStatus());
        ConnectionProvider verify = loader.connect(config);
        try {
            ResultSet rs = verify.getConnection().createStatement().executeQuery("SELECT COUNT(*) FROM dbo.schemaloom_target");
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            ResultSet temp = verify.getConnection().createStatement().executeQuery("SELECT COUNT(*) FROM sys.objects WHERE name = 'schemaloom_target_tmp' AND schema_id = SCHEMA_ID('dbo')");
            assertTrue(temp.next());
            assertEquals(0, temp.getInt(1));
            temp.close();
            rs.close();
        } finally { verify.close(); loader.close(); }
    }

    private static String value(String name, String fallback) { String v = System.getenv(name); return v == null || v.trim().isEmpty() ? fallback : v; }
    private static int intValue(String name, int fallback) { return Integer.parseInt(value(name, String.valueOf(fallback))); }
}
