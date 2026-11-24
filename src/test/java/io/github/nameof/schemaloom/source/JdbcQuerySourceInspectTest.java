package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.DataRecord;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * D1：探测列 Schema 的查询必须限制回传行数，避免大表全量进内存；
 * 正式读取与预览查询的行数语义不得被连带改变。
 */
public class JdbcQuerySourceInspectTest {

    @Test
    public void schemaInspectionSetsMaxRowsToOne() throws Exception {
        Fixture f = new Fixture("d1_schema");
        try {
            assertEquals(2, f.source.schema().getSchema().getFields().size());
            assertEquals("只应有一条探测语句，且限制回传 1 行",
                    Collections.singletonList(1), f.maxRowsPerStatement);
        } finally {
            f.close();
        }
    }

    @Test
    public void formalReadIsNotLimitedByInspection() throws Exception {
        Fixture f = new Fixture("d1_read");
        try {
            final List<DataRecord> rows = new ArrayList<DataRecord>();
            f.source.read(batch -> rows.addAll(batch.getRecords()));
            assertEquals(3, rows.size());
            assertEquals("探测限 1 行，正式读取不限行数",
                    Arrays.asList(1, 0), f.maxRowsPerStatement);
        } finally {
            f.close();
        }
    }

    @Test
    public void previewKeepsItsOwnMaxRows() throws Exception {
        Fixture f = new Fixture("d1_preview");
        try {
            assertEquals(2, f.source.readRows(2).size());
            assertEquals("探测限 1 行，预览沿用调用方传入的上限",
                    Arrays.asList(1, 2), f.maxRowsPerStatement);
        } finally {
            f.close();
        }
    }

    /** 每条 PreparedStatement 记录一次 setMaxRows；0 表示未调用（即不限制行数）。 */
    private static final class Fixture {
        final Connection actual;
        final List<Integer> maxRowsPerStatement = new ArrayList<Integer>();
        final JdbcQuerySource source;

        Fixture(String db) throws Exception {
            actual = DriverManager.getConnection("jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1");
            actual.createStatement().execute("CREATE TABLE orders (id INT, name VARCHAR(20))");
            actual.createStatement().execute("INSERT INTO orders VALUES (1, 'a'), (2, 'b'), (3, 'c')");
            Connection recording = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        try {
                            Object value = method.invoke(actual, args);
                            if (!"prepareStatement".equals(method.getName()) || !(value instanceof PreparedStatement))
                                return value;
                            final PreparedStatement statement = (PreparedStatement) value;
                            final int slot = maxRowsPerStatement.size();
                            maxRowsPerStatement.add(0);
                            return Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{PreparedStatement.class}, (sp, sm, sa) -> {
                                        if ("setMaxRows".equals(sm.getName()))
                                            maxRowsPerStatement.set(slot, (Integer) sa[0]);
                                        try {
                                            return sm.invoke(statement, sa);
                                        } catch (InvocationTargetException e) {
                                            throw e.getCause();
                                        }
                                    });
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            ConnectionProvider provider = new ConnectionProvider() {
                public Connection getConnection() { return recording; }
                public void close() { try { actual.close(); } catch (Exception ignored) { } }
            };
            source = new JdbcQuerySource(provider, "SELECT id, name FROM orders",
                    Collections.emptyList(), 100);
        }

        void close() { source.close(); }
    }
}
