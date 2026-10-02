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

public class OracleEtlIntegrationTest {
    @Test
    public void migratesTableAndReadsMetadata() throws Exception {
        String password = System.getenv("SCHEMALOOM_IT_ORACLE_PASSWORD");
        Assume.assumeTrue("set SCHEMALOOM_IT_ORACLE_PASSWORD to run Oracle integration test", password != null);
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.ORACLE,
                value("SCHEMALOOM_IT_ORACLE_HOST", "localhost"), intValue("SCHEMALOOM_IT_ORACLE_PORT", 11521),
                value("SCHEMALOOM_IT_ORACLE_DATABASE", "FREEPDB1"), null, "SCHEMALOOM", "SCHEMALOOM", password,
                value("SCHEMALOOM_IT_ORACLE_DRIVER_ID", "oracle23"), null);
        JdbcDriverLoader loader = new JdbcDriverLoader();
        ConnectionProvider setup = loader.connect(config);
        try {
            Statement st = setup.getConnection().createStatement();
            st.executeUpdate("BEGIN EXECUTE IMMEDIATE 'DROP VIEW SCHEMALOOM_TARGET_VIEW'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;");
            st.executeUpdate("BEGIN EXECUTE IMMEDIATE 'DROP VIEW SCHEMALOOM_SOURCE_VIEW'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;");
            st.executeUpdate("BEGIN EXECUTE IMMEDIATE 'DROP VIEW SCHEMALOOM_SOURCE_VIEW'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;");
            st.executeUpdate("BEGIN EXECUTE IMMEDIATE 'DROP TABLE SCHEMALOOM_SOURCE CASCADE CONSTRAINTS'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;");
            st.executeUpdate("BEGIN EXECUTE IMMEDIATE 'DROP TABLE SCHEMALOOM_TARGET CASCADE CONSTRAINTS'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;");
            st.executeUpdate("CREATE TABLE SCHEMALOOM_SOURCE (ID NUMBER(10) PRIMARY KEY, NAME VARCHAR2(100), AMOUNT NUMBER(12,2))");
            st.executeUpdate("CREATE TABLE SCHEMALOOM_TARGET (ID NUMBER(10) PRIMARY KEY, NAME VARCHAR2(100), AMOUNT NUMBER(12,2))");
            st.executeUpdate("INSERT INTO SCHEMALOOM_SOURCE VALUES (1, 'alpha', 10.50)");
            st.executeUpdate("INSERT INTO SCHEMALOOM_SOURCE VALUES (2, 'beta', 20.75)");
            st.executeUpdate("CREATE VIEW SCHEMALOOM_SOURCE_VIEW AS SELECT ID, NAME FROM SCHEMALOOM_SOURCE");
            st.executeUpdate("COMMIT");
            st.close();
            TableInfo info = new DatabaseMetadataService().getTable(setup, config.table("SCHEMALOOM_SOURCE"));
            assertEquals(3, info.getColumns().size());
            assertNotNull(info.getPrimaryKey());
            assertEquals(LogicalType.DECIMAL, info.getColumns().get(0).getLogicalType());
            assertEquals(Types.NUMERIC, info.getColumns().get(0).getJdbcType());
            assertTrue(new DatabaseMetadataService().getSchemaStatistics(setup, new SchemaInfo(null, "SCHEMALOOM")).getTotalTables() > 0);
            assertNotNull(new DatabaseMetadataService().getTableStatistics(setup, config.table("SCHEMALOOM_SOURCE")));
        } finally { setup.close(); }

        JdbcTableSource probe = new JdbcTableSource(config, "SCHEMALOOM_SOURCE", loader, 2);
        try { probe.read(batch -> assertFalse(batch.getRecords().isEmpty())); } finally { probe.close(); }

        EtlResult result = EtlTask.builder().source(new JdbcTableSource(config, "SCHEMALOOM_SOURCE", loader, 2))
                .target(new JdbcTableTarget(config, "SCHEMALOOM_TARGET", loader)).targetMode(TargetMode.APPEND).build().run();
        StringBuilder failures = new StringBuilder();
        for (EtlError error : result.getErrors()) failures.append(error.getStage()).append(": ").append(error.getMessage()).append("; ");
        assertEquals(failures.toString(), EtlStatus.SUCCESS, result.getStatus());
        assertEquals(2, result.getWritten());
        EtlResult view = new JdbcViewMigrationTask(config, config, "SCHEMALOOM_SOURCE_VIEW", "SCHEMALOOM_TARGET_VIEW", loader).run();
        assertEquals(String.valueOf(view.getErrors()), EtlStatus.SUCCESS, view.getStatus());
        ConnectionProvider verify = loader.connect(config);
        try {
            ResultSet rs = verify.getConnection().createStatement().executeQuery("SELECT COUNT(*) FROM SCHEMALOOM_TARGET");
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            rs.close();
        } finally { verify.close(); loader.close(); }
    }

    private static String value(String name, String fallback) { String v = System.getenv(name); return v == null || v.trim().isEmpty() ? fallback : v; }
    private static int intValue(String name, int fallback) { return Integer.parseInt(value(name, String.valueOf(fallback))); }
}
