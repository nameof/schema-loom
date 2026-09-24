package io.github.nameof.schemaloom.execution;

import io.github.nameof.schemaloom.api.SchemaLoomException;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class JdbcExecutionAdapterTest {
    @Test
    public void convertsResultHandlerRuntimeFailure() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:h2:mem:execution_adapter;DB_CLOSE_DELAY=-1");
        JdbcExecutionAdapter adapter = new JdbcExecutionAdapter(new ConnectionProvider() {
            public Connection getConnection() { return connection; }
            public void close() { try { connection.close(); } catch (Exception ignored) { } }
        });
        try {
            adapter.query("read test", "SELECT 1", null, 1, null, new JdbcExecutionAdapter.ResultSetHandler<Void>() {
                public Void extractData(ResultSet resultSet) {
                    throw new IllegalStateException("callback failure");
                }
            });
            fail("expected SchemaLoomException");
        } catch (SchemaLoomException expected) {
            assertSame(IllegalStateException.class, expected.getCause().getClass());
        } finally {
            connection.close();
        }
    }
}
