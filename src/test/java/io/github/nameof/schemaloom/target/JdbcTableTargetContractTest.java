package io.github.nameof.schemaloom.target;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import org.junit.Test;

import java.sql.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.Assert.*;

public class JdbcTableTargetContractTest {
    @Test public void acceptsSafeAppendAndAllowsNullableExtraColumn() throws Exception {
        JdbcTableTarget target = target("safe", "CREATE TABLE orders (id INT NOT NULL, name VARCHAR(100), created_at TIMESTAMP)");
        target.prepare(schema(new FieldSchema("id", LogicalType.INT16, false, null, null, null),
                new FieldSchema("name", LogicalType.STRING, true, 50, null, null)), TargetMode.APPEND);
        target.close();
    }

    @Test(expected = SchemaLoomException.class)
    public void rejectsNullableSourceForNotNullTarget() throws Exception {
        JdbcTableTarget target = target("nullable", "CREATE TABLE orders (name VARCHAR(100) NOT NULL)");
        target.prepare(schema(new FieldSchema("name", LogicalType.STRING, true, 50, null, null)), TargetMode.APPEND);
    }

    @Test(expected = SchemaLoomException.class)
    public void rejectsNarrowTargetString() throws Exception {
        JdbcTableTarget target = target("narrow", "CREATE TABLE orders (name VARCHAR(10))");
        target.prepare(schema(new FieldSchema("name", LogicalType.STRING, true, 20, null, null)), TargetMode.APPEND);
    }

    @Test(expected = SchemaLoomException.class)
    public void rejectsRequiredExtraTargetColumn() throws Exception {
        JdbcTableTarget target = target("required", "CREATE TABLE orders (id INT NOT NULL, required VARCHAR(10) NOT NULL)");
        target.prepare(schema(new FieldSchema("id", LogicalType.INT32, false, null, null, null)), TargetMode.APPEND);
    }

    @Test public void replaceKeepsOldTableUntilCloseAndRenamesTemporaryTable() throws Exception {
        JdbcTableTarget target = target("replace", "CREATE TABLE orders (old_value VARCHAR(10))");
        target.prepare(schema(new FieldSchema("id", LogicalType.INT32, false, null, null, null)), TargetMode.REPLACE);
        Connection check = DriverManager.getConnection("jdbc:h2:mem:replace;MODE=MySQL;DB_CLOSE_DELAY=-1");
        assertEquals(1, count(check, "ORDERS"));
        assertEquals(1, count(check, "ORDERS_TMP"));
        check.close();
        target.close();
        Connection after = DriverManager.getConnection("jdbc:h2:mem:replace;MODE=MySQL;DB_CLOSE_DELAY=-1");
        assertEquals(1, count(after, "ORDERS"));
        assertEquals(0, count(after, "ORDERS_TMP"));
        after.close();
    }

    @Test public void replaceWriteFailureCleansTemporaryAndKeepsOldTable() throws Exception {
        JdbcTableTarget target = target("replace_failure", "CREATE TABLE orders (old_value VARCHAR(10) NOT NULL)");
        RecordSchema writeSchema = new RecordSchema(Collections.singletonList(new FieldSchema("id", LogicalType.INT32, false, null, null, null)));
        target.prepare(SchemaDescriptor.of(writeSchema), TargetMode.REPLACE);
        try {
            target.write(new RecordBatch(writeSchema,
                    Collections.singletonList(new DataRecord(writeSchema, Collections.<Object>singletonList(null)))));
            fail("expected write failure");
        } catch (SchemaLoomException expected) {
            // 由 close 负责清理临时表。
        }
        target.close();
        Connection after = DriverManager.getConnection("jdbc:h2:mem:replace_failure;MODE=MySQL;DB_CLOSE_DELAY=-1");
        assertEquals(1, count(after, "ORDERS"));
        assertEquals(0, count(after, "ORDERS_TMP"));
        after.close();
    }

    @Test public void runtimeBindingFailureRollsBackWholeBatchAndRestoresAutoCommit() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:h2:mem:runtime_rollback;MODE=MySQL;DB_CLOSE_DELAY=-1");
        connection.createStatement().execute("CREATE TABLE orders (id INT)");
        final Connection bindingFailureConnection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        Object value = method.invoke(connection, args);
                        if (!"prepareStatement".equals(method.getName()) || !(value instanceof PreparedStatement)) return value;
                        PreparedStatement statement = (PreparedStatement) value;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (statementProxy, statementMethod, statementArgs) -> {
                                    if ("setInt".equals(statementMethod.getName()))
                                        throw new IllegalArgumentException("simulated binding failure");
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
        JdbcTableTarget target = new JdbcTableTarget(new ConnectionProvider() {
            public Connection getConnection() { return bindingFailureConnection; }
            public void close() { try { connection.close(); } catch (SQLException ignored) { } }
        }, new QualifiedTableName(null, null, "ORDERS"), DatabaseType.MYSQL);
        RecordSchema writeSchema = new RecordSchema(Collections.singletonList(
                new FieldSchema("id", LogicalType.INT32, false, null, null, null)));
        target.prepare(SchemaDescriptor.of(writeSchema), TargetMode.APPEND);

        try {
            target.write(new RecordBatch(writeSchema, Arrays.asList(
                    new DataRecord(writeSchema, Collections.<Object>singletonList(1)),
                    new DataRecord(writeSchema, Collections.<Object>singletonList(2)))));
            fail("expected binding failure");
        } catch (SchemaLoomException expected) {
            assertTrue(expected.getMessage().contains("write target batch"));
        }

        assertTrue(connection.getAutoCommit());
        ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM orders");
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1));
        rows.close();
        target.close();
    }

    private int count(Connection c, String table) throws SQLException {
        ResultSet rs = c.getMetaData().getTables(null, null, table, new String[]{"TABLE"});
        try { return rs.next() ? 1 : 0; } finally { rs.close(); }
    }

    @Test(expected = SchemaLoomException.class)
    public void rejectsExistingViewAsTarget() throws Exception {
        JdbcTableTarget target = target("view_target", "CREATE VIEW orders AS SELECT 1 AS id");
        target.prepare(schema(new FieldSchema("id", LogicalType.INT32, false, null, null, null)), TargetMode.APPEND);
    }

    private JdbcTableTarget target(String database, String ddl) throws SQLException {
        final Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + database + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        Statement statement = connection.createStatement();
        statement.execute(ddl);
        statement.close();
        return new JdbcTableTarget(new ConnectionProvider() {
            public Connection getConnection() { return connection; }
            public void close() { try { connection.close(); } catch (SQLException ignored) { } }
        }, new QualifiedTableName(null, null, "ORDERS"), DatabaseType.MYSQL);
    }

    private SchemaDescriptor schema(FieldSchema... fields) {
        return SchemaDescriptor.of(new RecordSchema(Arrays.asList(fields)));
    }
}
