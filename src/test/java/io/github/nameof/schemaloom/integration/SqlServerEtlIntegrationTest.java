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
    public void migratesTableAndReadsMetadata() throws Exception {
        String password = System.getenv("SCHEMALOOM_IT_SQLSERVER_PASSWORD");
        Assume.assumeTrue("set SCHEMALOOM_IT_SQLSERVER_PASSWORD to run SQL Server integration test", password != null);
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.SQL_SERVER,
                value("SCHEMALOOM_IT_SQLSERVER_HOST", "localhost"), intValue("SCHEMALOOM_IT_SQLSERVER_PORT", 11433),
                value("SCHEMALOOM_IT_SQLSERVER_DATABASE", "master"), "sa", password,
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
        assertEquals(EtlStatus.SUCCESS, result.getStatus());
        assertEquals(2, result.getWritten());
        EtlResult view = new JdbcViewMigrationTask(config, config, "schemaloom_source_view", "schemaloom_target_view", loader).run();
        assertEquals(EtlStatus.SUCCESS, view.getStatus());
        ConnectionProvider verify = loader.connect(config);
        try {
            ResultSet rs = verify.getConnection().createStatement().executeQuery("SELECT COUNT(*) FROM dbo.schemaloom_target");
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            assertTrue(new DatabaseMetadataService().listTables(verify, new MetadataQuery("master", "dbo", "schemaloom_target_tmp")).isEmpty());
            rs.close();
        } finally { verify.close(); loader.close(); }
    }

    private static String value(String name, String fallback) { String v = System.getenv(name); return v == null || v.trim().isEmpty() ? fallback : v; }
    private static int intValue(String name, int fallback) { return Integer.parseInt(value(name, String.valueOf(fallback))); }
}
