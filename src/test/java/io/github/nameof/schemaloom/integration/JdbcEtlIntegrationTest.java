package io.github.nameof.schemaloom.integration;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.engine.EtlTask;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.metadata.DatabaseMetadataService;
import io.github.nameof.schemaloom.metadata.TableInfo;
import io.github.nameof.schemaloom.metadata.SchemaInfo;
import io.github.nameof.schemaloom.migration.JdbcViewMigrationTask;
import io.github.nameof.schemaloom.source.JdbcTableSource;
import io.github.nameof.schemaloom.target.JdbcTableTarget;
import org.junit.Assume;
import org.junit.Test;

import java.sql.*;
import java.util.Properties;
import static org.junit.Assert.*;

/**
 * Runs against a real MySQL instance when SCHEMALOOM_IT_MYSQL_HOST is configured.
 */
public class JdbcEtlIntegrationTest {
    @Test
    public void migratesSelectedTableAndVerifiesRows() throws Exception {
        String host = System.getenv("SCHEMALOOM_IT_MYSQL_HOST");
        String portValue = System.getenv("SCHEMALOOM_IT_MYSQL_PORT");
        String database = System.getenv("SCHEMALOOM_IT_MYSQL_DATABASE");
        String user = System.getenv("SCHEMALOOM_IT_MYSQL_USER");
        String password = System.getenv("SCHEMALOOM_IT_MYSQL_PASSWORD");
        String driverId = System.getenv("SCHEMALOOM_IT_MYSQL_DRIVER_ID");
        Assume.assumeTrue("set MySQL host, database, user and password variables", host != null && database != null && user != null && password != null);
        int port = portValue == null || portValue.trim().isEmpty() ? 3306 : Integer.parseInt(portValue);
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("allowPublicKeyRetrieval", "true");
        connectionProperties.setProperty("useSSL", "false");
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.MYSQL, host, port, database, user, password, driverId, connectionProperties);
        JdbcDriverLoader loader = new JdbcDriverLoader();
        ConnectionProvider setup = loader.connect(config);
        Connection c = setup.getConnection();
        Statement st = c.createStatement();
        st.execute("DROP VIEW IF EXISTS schemaloom_target_view");
        st.execute("DROP VIEW IF EXISTS schemaloom_source_view");
        st.execute("DROP TABLE IF EXISTS schemaloom_target");
        st.execute("DROP TABLE IF EXISTS schemaloom_target_tmp");
        st.execute("DROP TABLE IF EXISTS schemaloom_source");
        st.execute("CREATE TABLE schemaloom_source (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2))");
        st.execute("INSERT INTO schemaloom_source VALUES (1, 'alpha', 10.50), (2, 'beta', 20.75), (3, 'gamma', 0.00)");
        st.execute("CREATE VIEW schemaloom_source_view AS SELECT id, name FROM schemaloom_source");
        st.execute("CREATE TABLE schemaloom_target (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2))");
        st.execute("INSERT INTO schemaloom_target VALUES (99, 'old', 99.99)");
        st.close();
        TableInfo metadata = new DatabaseMetadataService().getTable(setup,
                new QualifiedTableName(null, null, "schemaloom_source"));
        assertEquals(3, metadata.getColumns().size());
        assertEquals(database, metadata.getName().getCatalog());
        assertNull(metadata.getName().getSchema());
        assertEquals(LogicalType.DECIMAL, metadata.getColumns().get(2).getLogicalType());
        assertEquals(Integer.valueOf(12), metadata.getColumns().get(2).getPrecision());
        assertEquals(Integer.valueOf(2), metadata.getColumns().get(2).getScale());
        assertNotNull(metadata.getPrimaryKey());
        assertEquals("id", metadata.getPrimaryKey().getColumns().get(0));
        assertTrue(new DatabaseMetadataService().getSchemaStatistics(setup, new SchemaInfo(database, null)).getTotalTables() > 0);
        assertNotNull(new DatabaseMetadataService().getTableStatistics(setup, new QualifiedTableName(database, null, "schemaloom_source")));
        setup.close();

        EtlResult result = EtlTask.builder()
                .source(new JdbcTableSource(config, "schemaloom_source", loader, 2))
                .target(new JdbcTableTarget(config, "schemaloom_target", loader))
                .targetMode(TargetMode.REPLACE).build().run();
        assertEquals(EtlStatus.SUCCESS, result.getStatus());
        assertEquals(3, result.getRead());
        assertEquals(3, result.getWritten());
        EtlResult view = new JdbcViewMigrationTask(config, config, "schemaloom_source_view", "schemaloom_target_view", loader).run();
        assertEquals(EtlStatus.SUCCESS, view.getStatus());

        ConnectionProvider verify = loader.connect(config);
        ResultSet rs = verify.getConnection().createStatement().executeQuery("SELECT id, name, amount FROM schemaloom_target ORDER BY id");
        int count = 0;
        while (rs.next()) {
            count++;
            assertEquals(count, rs.getInt("id"));
            assertNotNull(rs.getString("name"));
            assertEquals(new java.math.BigDecimal(count == 1 ? "10.50" : count == 2 ? "20.75" : "0.00"), rs.getBigDecimal("amount"));
        }
        rs.close();
        assertEquals(3, count);
        TableInfo targetMetadata = new DatabaseMetadataService().getTable(verify,
                new QualifiedTableName(null, null, "schemaloom_target"));
        assertNotNull(targetMetadata.getPrimaryKey());
        assertEquals("id", targetMetadata.getPrimaryKey().getColumns().get(0));
        assertTrue(new DatabaseMetadataService().listTables(verify,
                new io.github.nameof.schemaloom.metadata.MetadataQuery(database, null, "schemaloom_target_tmp")).isEmpty());
        verify.close();
        loader.close();
    }

    @Test
    public void migratesIdentityGeneratedDefaultsAndIndexes() throws Exception {
        String host = System.getenv("SCHEMALOOM_IT_MYSQL_HOST");
        String database = System.getenv("SCHEMALOOM_IT_MYSQL_DATABASE");
        String user = System.getenv("SCHEMALOOM_IT_MYSQL_USER");
        String password = System.getenv("SCHEMALOOM_IT_MYSQL_PASSWORD");
        Assume.assumeTrue("set MySQL connection variables to run integration test", host != null && database != null && user != null && password != null);
        int port = Integer.parseInt(valueOrDefault("SCHEMALOOM_IT_MYSQL_PORT", "3306"));
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("allowPublicKeyRetrieval", "true");
        connectionProperties.setProperty("useSSL", "false");
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.MYSQL, host, port, database, user, password,
                System.getenv("SCHEMALOOM_IT_MYSQL_DRIVER_ID"), connectionProperties);
        JdbcDriverLoader loader = new JdbcDriverLoader();
        ConnectionProvider setup = loader.connect(config);
        try {
            Statement st = setup.getConnection().createStatement();
            st.execute("DROP TABLE IF EXISTS schemaloom_boundary_target");
            st.execute("DROP TABLE IF EXISTS schemaloom_boundary");
            st.execute("CREATE TABLE schemaloom_boundary (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(40) NOT NULL DEFAULT 'fallback', amount DECIMAL(10,2) DEFAULT 0.00, derived DECIMAL(10,2) GENERATED ALWAYS AS (amount + 1) STORED) COMMENT='boundary table'");
            st.execute("CREATE UNIQUE INDEX schemaloom_boundary_name_uq ON schemaloom_boundary(name)");
            st.execute("INSERT INTO schemaloom_boundary (name, amount) VALUES ('alpha', 12.34)");
            st.close();
            TableInfo info = new DatabaseMetadataService().getTable(setup, config.table("schemaloom_boundary"));
            assertTrue(info.getColumns().get(0).isAutoIncremented());
            assertTrue(info.getColumns().get(3).isGenerated());
            assertEquals("boundary table", info.getRemarks());
            assertTrue(info.getIndexes().stream().anyMatch(index -> index.isUnique() && "schemaloom_boundary_name_uq".equalsIgnoreCase(index.getName())));
            st = setup.getConnection().createStatement();
            st.execute("CREATE TABLE schemaloom_boundary_target (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(40) NOT NULL DEFAULT 'fallback', amount DECIMAL(10,2) DEFAULT 0.00, derived DECIMAL(10,2) GENERATED ALWAYS AS (amount + 1) STORED)");
            st.execute("CREATE UNIQUE INDEX schemaloom_boundary_name_uq ON schemaloom_boundary_target(name)");
            st.close();
        } finally {
            setup.close();
        }
        EtlResult result = EtlTask.builder()
                .source(new JdbcTableSource(config, "schemaloom_boundary", loader, 2))
                .target(new JdbcTableTarget(config, "schemaloom_boundary_target", loader, MetadataErrorPolicy.FAIL))
                .targetMode(TargetMode.APPEND).build().run();
        assertEquals(result.getErrors().isEmpty() ? "" : result.getErrors().get(0).getStage() + ": " + result.getErrors().get(0).getMessage() + " type=" + result.getErrors().get(0).getType(), EtlStatus.SUCCESS, result.getStatus());
        ConnectionProvider verify = loader.connect(config);
        try {
            ResultSet rs = verify.getConnection().createStatement().executeQuery("SELECT id, name, amount, derived FROM schemaloom_boundary_target");
            assertTrue(rs.next());
            assertEquals("alpha", rs.getString("name"));
            assertEquals(new java.math.BigDecimal("12.34"), rs.getBigDecimal("amount"));
            assertEquals(new java.math.BigDecimal("13.34"), rs.getBigDecimal("derived"));
            rs.close();
        } finally {
            verify.close();
            loader.close();
        }
    }

    private static String valueOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }}
