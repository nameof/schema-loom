package io.github.nameof.schemaloom.integration;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.engine.EtlTask;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.metadata.DatabaseMetadataService;
import io.github.nameof.schemaloom.metadata.TableInfo;
import io.github.nameof.schemaloom.source.JdbcTableSource;
import io.github.nameof.schemaloom.target.JdbcTableTarget;
import org.junit.Assume;
import org.junit.Test;

import java.sql.*;
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
        JdbcConnectionConfig config = new JdbcConnectionConfig(DatabaseType.MYSQL, host, port, database, user, password, driverId, null);
        JdbcDriverLoader loader = new JdbcDriverLoader();
        ConnectionProvider setup = loader.connect(config);
        Connection c = setup.getConnection();
        Statement st = c.createStatement();
        st.execute("DROP TABLE IF EXISTS schemaloom_target");
        st.execute("DROP TABLE IF EXISTS schemaloom_source");
        st.execute("CREATE TABLE schemaloom_source (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2))");
        st.execute("INSERT INTO schemaloom_source VALUES (1, 'alpha', 10.50), (2, 'beta', 20.75), (3, 'gamma', 0.00)");
        st.close();
        TableInfo metadata = new DatabaseMetadataService().getTable(setup,
                new QualifiedTableName(null, null, "schemaloom_source"));
        assertEquals(3, metadata.getColumns().size());
        assertNotNull(metadata.getPrimaryKey());
        assertEquals("id", metadata.getPrimaryKey().getColumns().get(0));
        setup.close();

        EtlResult result = EtlTask.builder()
                .source(new JdbcTableSource(loader.connect(config), new QualifiedTableName(null, null, "schemaloom_source"), 2))
                .target(new JdbcTableTarget(loader.connect(config), "schemaloom_target", DatabaseType.MYSQL))
                .targetMode(TargetMode.REPLACE).build().run();
        assertEquals(EtlStatus.SUCCESS, result.getStatus());
        assertEquals(3, result.getRead());
        assertEquals(3, result.getWritten());

        ConnectionProvider verify = loader.connect(config);
        ResultSet rs = verify.getConnection().createStatement().executeQuery("SELECT id, name, amount FROM schemaloom_target ORDER BY id");
        int count = 0;
        while (rs.next()) {
            count++;
            assertEquals(count, rs.getInt("id"));
            assertNotNull(rs.getString("name"));
        }
        rs.close();
        assertEquals(3, count);
        TableInfo targetMetadata = new DatabaseMetadataService().getTable(verify,
                new QualifiedTableName(null, null, "schemaloom_target"));
        assertNotNull(targetMetadata.getPrimaryKey());
        assertEquals("id", targetMetadata.getPrimaryKey().getColumns().get(0));
        verify.close();
        loader.close();
    }

}
