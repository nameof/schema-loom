package io.github.nameof.schemaloom.execution;

import io.github.nameof.schemaloom.api.SchemaLoomException;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collections;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
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

    @Test
    public void closesStatementWhenQueryConfigurationOrBindingFails() throws Exception {
        assertStatementClosed("setFetchSize", Collections.<Object>emptyList(), null);
        assertStatementClosed("setMaxRows", Collections.<Object>emptyList(), 1);
        assertStatementClosed("setObject", Collections.<Object>singletonList("value"), null);
    }

    private void assertStatementClosed(final String failingMethod, java.util.List<Object> params,
                                       Integer maxRows) throws Exception {
        final Connection actual = DriverManager.getConnection("jdbc:h2:mem:execution_" + failingMethod + ";DB_CLOSE_DELAY=-1");
        final boolean[] statementClosed = {false};
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        Object value = method.invoke(actual, args);
                        if (!"prepareStatement".equals(method.getName()) || !(value instanceof PreparedStatement)) return value;
                        PreparedStatement statement = (PreparedStatement) value;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (statementProxy, statementMethod, statementArgs) -> {
                                    if (failingMethod.equals(statementMethod.getName()))
                                        throw new SQLException("simulated " + failingMethod + " failure");
                                    if ("close".equals(statementMethod.getName())) statementClosed[0] = true;
                                    try {
                                        return statementMethod.invoke(statement, statementArgs);
                                    } catch (InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        JdbcExecutionAdapter adapter = new JdbcExecutionAdapter(provider(connection));
        try {
            adapter.query("configure query", "SELECT ?", params, 1, maxRows,
                    resultSet -> null);
            fail("expected SchemaLoomException");
        } catch (SchemaLoomException expected) {
            assertTrue(expected.getMessage().contains("configure query"));
            assertTrue(statementClosed[0]);
        } finally {
            actual.close();
        }
    }

    private ConnectionProvider provider(final Connection connection) {
        return new ConnectionProvider() {
            public Connection getConnection() { return connection; }
            public void close() { try { connection.close(); } catch (Exception ignored) { } }
        };
    }
}
