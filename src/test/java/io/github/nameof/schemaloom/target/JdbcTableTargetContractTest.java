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

    @Test public void replaceCommitsOnCloseAndRenamesTemporaryTable() throws Exception {
        JdbcTableTarget target = target("replace", "CREATE TABLE orders (old_value VARCHAR(10))", "INSERT INTO orders VALUES ('keep')");
        target.prepare(schema(new FieldSchema("id", LogicalType.INT32, false, null, null, null)), TargetMode.REPLACE);
        Connection check = DriverManager.getConnection("jdbc:h2:mem:replace;MODE=MySQL;DB_CLOSE_DELAY=-1");
        assertEquals(1, count(check, "ORDERS"));
        assertEquals(1, count(check, "ORDERS_TMP"));
        check.close();
        target.close();
        Connection after = DriverManager.getConnection("jdbc:h2:mem:replace;MODE=MySQL;DB_CLOSE_DELAY=-1");
        assertEquals(1, count(after, "ORDERS"));
        assertEquals(0, count(after, "ORDERS_TMP"));
        assertEquals(0, count(after, "ORDERS_OLD"));
        assertEquals("0", singleValue(after, "SELECT COUNT(*) FROM orders"));
        after.close();
    }

    /** A2：以 close(false) 关闭（任务失败语义）时保留原表、只清理临时表。 */
    @Test public void replaceCloseWithoutCommitKeepsOriginalTableAndDropsTemporary() throws Exception {
        JdbcTableTarget target = target("replace_no_finish", "CREATE TABLE orders (old_value VARCHAR(10))", "INSERT INTO orders VALUES ('keep')");
        RecordSchema writeSchema = new RecordSchema(Collections.singletonList(new FieldSchema("id", LogicalType.INT32, false, null, null, null)));
        target.prepare(SchemaDescriptor.of(writeSchema), TargetMode.REPLACE);
        target.write(new RecordBatch(writeSchema,
                Collections.singletonList(new DataRecord(writeSchema, Collections.<Object>singletonList(7)))));
        target.close(false);
        Connection after = DriverManager.getConnection("jdbc:h2:mem:replace_no_finish;MODE=MySQL;DB_CLOSE_DELAY=-1");
        assertEquals(1, count(after, "ORDERS"));
        assertEquals(0, count(after, "ORDERS_TMP"));
        assertEquals("keep", singleValue(after, "SELECT old_value FROM orders"));
        after.close();
    }

    /** README 契约：close 应可重复调用——清理与换表两条路径的第二次 close 都不得抛异常或重复改动。 */
    @Test public void closeIsRepeatable() throws Exception {
        // 场景一：close(false) 清理临时表后再 close()，不得再尝试清理或换表。
        JdbcTableTarget discarded = target("repeat_discard", "CREATE TABLE orders (old_value VARCHAR(10))", "INSERT INTO orders VALUES ('keep')");
        RecordSchema writeSchema = new RecordSchema(Collections.singletonList(new FieldSchema("id", LogicalType.INT32, false, null, null, null)));
        discarded.prepare(SchemaDescriptor.of(writeSchema), TargetMode.REPLACE);
        discarded.close(false);
        discarded.close();
        Connection afterDiscard = DriverManager.getConnection("jdbc:h2:mem:repeat_discard;MODE=MySQL;DB_CLOSE_DELAY=-1");
        assertEquals("清理路径重复 close 后原表必须保留", 1, count(afterDiscard, "ORDERS"));
        assertEquals("keep", singleValue(afterDiscard, "SELECT old_value FROM orders"));
        assertEquals(0, count(afterDiscard, "ORDERS_TMP"));
        afterDiscard.close();

        // 场景二：close()（提交换表）后再 close()，不得重复换表或误删已提交的表。
        JdbcTableTarget committed = target("repeat_commit", "CREATE TABLE orders (old_value VARCHAR(10))", "INSERT INTO orders VALUES ('keep')");
        committed.prepare(SchemaDescriptor.of(writeSchema), TargetMode.REPLACE);
        committed.write(new RecordBatch(writeSchema,
                Collections.singletonList(new DataRecord(writeSchema, Collections.<Object>singletonList(7)))));
        committed.close();
        committed.close();
        Connection afterCommit = DriverManager.getConnection("jdbc:h2:mem:repeat_commit;MODE=MySQL;DB_CLOSE_DELAY=-1");
        assertEquals("提交路径重复 close 后新数据必须在", 1, count(afterCommit, "ORDERS"));
        assertEquals(0, count(afterCommit, "ORDERS_TMP"));
        assertEquals(0, count(afterCommit, "ORDERS_OLD"));
        afterCommit.close();
    }

    /** A1：临时表接位失败时必须回滚原表，且不得顺手删掉临时表里的新数据。 */
    @Test public void replaceCommitFailureRestoresOriginalTableAndKeepsTemporary() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:h2:mem:replace_commit_failure;MODE=MySQL;DB_CLOSE_DELAY=-1");
        Statement seed = connection.createStatement();
        seed.execute("CREATE TABLE orders (old_value VARCHAR(10))");
        seed.execute("INSERT INTO orders VALUES ('keep')");
        seed.close();
        JdbcTableTarget target = new JdbcTableTarget(provider(failingRename(connection, "_TMP"), connection), null,
                new QualifiedTableName(null, null, "ORDERS"), DatabaseType.MYSQL);
        RecordSchema writeSchema = new RecordSchema(Collections.singletonList(new FieldSchema("id", LogicalType.INT32, false, null, null, null)));
        target.prepare(SchemaDescriptor.of(writeSchema), TargetMode.REPLACE);
        target.write(new RecordBatch(writeSchema,
                Collections.singletonList(new DataRecord(writeSchema, Collections.<Object>singletonList(7)))));
        try {
            target.close();
            fail("expected rename failure");
        } catch (SchemaLoomException expected) {
            // 换表失败由 close 抛出，A3 会把状态降为 FAILED；现场留给人工处置。
        }

        Connection after = DriverManager.getConnection("jdbc:h2:mem:replace_commit_failure;MODE=MySQL;DB_CLOSE_DELAY=-1");
        assertEquals(1, count(after, "ORDERS"));
        assertEquals("keep", singleValue(after, "SELECT old_value FROM orders"));
        assertEquals(0, count(after, "ORDERS_OLD"));
        assertEquals(1, count(after, "ORDERS_TMP"));
        after.close();
    }

    /**
     * E5 复现/回归：显式 close(true) 必须无条件服从——模拟 ISOLATE_AND_CONTINUE 降级场景
     * （坏行写失败置闩，好行重写成功）后，显式提交必须换表，不得被内部 writeFailed 静默否决。
     */
    @Test public void replaceCloseWithExplicitCommitSwapsDespiteEarlierWriteFailure() throws Exception {
        JdbcTableTarget target = target("replace_explicit_commit", "CREATE TABLE orders (old_value VARCHAR(10))", "INSERT INTO orders VALUES ('keep')");
        RecordSchema writeSchema = new RecordSchema(Collections.singletonList(
                new FieldSchema("old_value", LogicalType.STRING, false, 10, null, null)));
        target.prepare(SchemaDescriptor.of(writeSchema), TargetMode.REPLACE);
        try {
            // 坏行：违反 NOT NULL，write 抛异常并置位内部闩
            target.write(new RecordBatch(writeSchema,
                    Collections.singletonList(new DataRecord(writeSchema, Collections.<Object>singletonList(null)))));
            fail("expected write failure");
        } catch (SchemaLoomException expected) {
            // 上层（EtlTask ISOLATE_AND_CONTINUE）捕获后降级逐行重写
        }
        target.write(new RecordBatch(writeSchema,
                Collections.singletonList(new DataRecord(writeSchema, Collections.<Object>singletonList("fine")))));
        // EtlTask 判 PARTIAL 后以 close(true) 显式提交：必须换表，目标表 = 好行全集
        target.close(true);
        Connection after = DriverManager.getConnection("jdbc:h2:mem:replace_explicit_commit;MODE=MySQL;DB_CLOSE_DELAY=-1");
        assertEquals(1, count(after, "ORDERS"));
        assertEquals(0, count(after, "ORDERS_TMP"));
        assertEquals(0, count(after, "ORDERS_OLD"));
        assertEquals("fine", singleValue(after, "SELECT old_value FROM orders"));
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
        }, null, new QualifiedTableName(null, null, "ORDERS"), DatabaseType.MYSQL);
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

    private String singleValue(Connection c, String sql) throws SQLException {
        Statement statement = c.createStatement();
        try {
            ResultSet rs = statement.executeQuery(sql);
            try { return rs.next() ? rs.getString(1) : null; } finally { rs.close(); }
        } finally {
            statement.close();
        }
    }

    private ConnectionProvider provider(final Connection connection, final Connection delegate) {
        return new ConnectionProvider() {
            public Connection getConnection() { return connection; }
            public void close() { try { delegate.close(); } catch (SQLException ignored) { } }
        };
    }

    /** 让包含 marker 的 rename 语句失败，模拟「临时表接位失败」。 */
    private Connection failingRename(final Connection connection, final String marker) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object value;
                    try {
                        value = method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (!"createStatement".equals(method.getName()) || !(value instanceof Statement)) return value;
                    final Statement delegate = (Statement) value;
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                            (statementProxy, statementMethod, statementArgs) -> {
                                if (statementMethod.getName().startsWith("execute")
                                        && statementArgs != null && statementArgs.length > 0
                                        && statementArgs[0] instanceof String) {
                                    String sql = ((String) statementArgs[0]).toUpperCase(Locale.ENGLISH);
                                    if (sql.contains("RENAME") && sql.contains(marker))
                                        throw new SQLException("simulated rename failure");
                                }
                                try {
                                    return statementMethod.invoke(delegate, statementArgs);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                });
    }

    @Test(expected = SchemaLoomException.class)
    public void rejectsExistingViewAsTarget() throws Exception {
        JdbcTableTarget target = target("view_target", "CREATE VIEW orders AS SELECT 1 AS id");
        target.prepare(schema(new FieldSchema("id", LogicalType.INT32, false, null, null, null)), TargetMode.APPEND);
    }

    private JdbcTableTarget target(String database, String ddl, String... extra) throws SQLException {
        final Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + database + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        Statement statement = connection.createStatement();
        statement.execute(ddl);
        for (String sql : extra) statement.execute(sql);
        statement.close();
        return new JdbcTableTarget(new ConnectionProvider() {
            public Connection getConnection() { return connection; }
            public void close() { try { connection.close(); } catch (SQLException ignored) { } }
        }, null, new QualifiedTableName(null, null, "ORDERS"), DatabaseType.MYSQL);
    }

    private SchemaDescriptor schema(FieldSchema... fields) {
        return SchemaDescriptor.of(new RecordSchema(Arrays.asList(fields)));
    }
}
