package io.github.nameof.schemaloom.integration;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.engine.EtlTask;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.source.JdbcTableSource;
import io.github.nameof.schemaloom.target.JdbcTableTarget;
import org.junit.Assume;
import org.junit.Test;

import java.sql.*;

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
        Assume.assumeTrue("set SCHEMALOOM_IT_MYSQL_URL/USER/PASSWORD to run MySQL integration test", url != null && user != null && password != null);
        JdbcConnectionProvider setup = new JdbcConnectionProvider(url, user, password);
        Connection c = setup.getConnection();
        Statement st = c.createStatement();
        st.execute("DROP TABLE IF EXISTS schemaloom_target");
        st.execute("DROP TABLE IF EXISTS schemaloom_source");
        st.execute("CREATE TABLE schemaloom_source (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2))");
        st.execute("INSERT INTO schemaloom_source VALUES (1, 'alpha', 10.50), (2, 'beta', 20.75), (3, 'gamma', 0.00)");
        st.close();
        setup.close();

        EtlResult result = EtlTask.builder()
                .source(new JdbcTableSource(new JdbcConnectionProvider(url, user, password), new QualifiedTableName(null, null, "schemaloom_source"), 2))
                .target(new JdbcTableTarget(new JdbcConnectionProvider(url, user, password), "schemaloom_target", DatabaseType.MYSQL))
                .targetMode(TargetMode.REPLACE).batchSize(2).build().run();
        assertEquals(EtlStatus.SUCCESS, result.getStatus());
        assertEquals(3, result.getRead());
        assertEquals(3, result.getWritten());

        JdbcConnectionProvider verify = new JdbcConnectionProvider(url, user, password);
        ResultSet rs = verify.getConnection().createStatement().executeQuery("SELECT id, name, amount FROM schemaloom_target ORDER BY id");
        int count = 0;
        while (rs.next()) {
            count++;
            assertEquals(count, rs.getInt("id"));
            assertNotNull(rs.getString("name"));
        }
        rs.close();
        verify.close();
        assertEquals(3, count);
    }
}
