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
import java.util.Properties;

import static org.junit.Assert.*;

/**
 * Runs against a real MySQL instance when SCHEMALOOM_IT_MYSQL_URL is configured.
 */
public class JdbcEtlIntegrationTest {
    @Test
    public void migratesSelectedTableAndVerifiesRows() throws Exception {
        String url = System.getenv("SCHEMALOOM_IT_MYSQL_URL");
        String user = System.getenv("SCHEMALOOM_IT_MYSQL_USER");
        String password = System.getenv("SCHEMALOOM_IT_MYSQL_PASSWORD");
        String driverId = System.getenv("SCHEMALOOM_IT_MYSQL_DRIVER_ID");
        Assume.assumeTrue("set MySQL connection variables and SCHEMALOOM_IT_MYSQL_DRIVER_ID", url != null && user != null && password != null && driverId != null);
        JdbcDriverLoader loader = new JdbcDriverLoader();
        ConnectionProvider setup = connection(loader, url, user, password, driverId);
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
                .source(new JdbcTableSource(connection(loader, url, user, password, driverId), new QualifiedTableName(null, null, "schemaloom_source"), 2))
                .target(new JdbcTableTarget(connection(loader, url, user, password, driverId), "schemaloom_target", DatabaseType.MYSQL))
                .targetMode(TargetMode.REPLACE).batchSize(2).build().run();
        assertEquals(EtlStatus.SUCCESS, result.getStatus());
        assertEquals(3, result.getRead());
        assertEquals(3, result.getWritten());

        ConnectionProvider verify = connection(loader, url, user, password, driverId);
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

    private static ConnectionProvider connection(JdbcDriverLoader loader, String url, String user, String password, String driverId) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        return loader.connect(DatabaseType.MYSQL, url, driverId, properties);
    }
}
