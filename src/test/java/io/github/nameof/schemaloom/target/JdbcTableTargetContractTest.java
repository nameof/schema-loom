package io.github.nameof.schemaloom.target;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import org.junit.Test;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

import static org.junit.Assert.*;

public class JdbcTableTargetContractTest {
    @Test public void acceptsSafeAppendAndAllowsNullableExtraColumn() {
        TargetFixture fixture = TargetFixture.existing(
                column("id", Types.INTEGER, 10, 0, "NO", null, "NO", "NO"),
                column("name", Types.VARCHAR, 100, 0, "YES", null, "NO", "NO"),
                column("created_at", Types.TIMESTAMP, 0, 0, "YES", null, "NO", "NO"));
        JdbcTableTarget target = fixture.target();
        target.prepare(new RecordSchema(Arrays.asList(
                new FieldSchema("id", LogicalType.INT16, false, null, null, null),
                new FieldSchema("name", LogicalType.STRING, true, 50, null, null))), TargetMode.APPEND);
        assertTrue(fixture.sql.isEmpty());
    }

    @Test(expected = SchemaLoomException.class)
    public void rejectsNullableSourceForNotNullTarget() {
        TargetFixture fixture = TargetFixture.existing(
                column("name", Types.VARCHAR, 100, 0, "NO", null, "NO", "NO"));
        fixture.target().prepare(new RecordSchema(Collections.singletonList(
                new FieldSchema("name", LogicalType.STRING, true, 50, null, null))), TargetMode.APPEND);
    }

    @Test(expected = SchemaLoomException.class)
    public void rejectsNarrowTargetString() {
        TargetFixture fixture = TargetFixture.existing(
                column("name", Types.VARCHAR, 10, 0, "YES", null, "NO", "NO"));
        fixture.target().prepare(new RecordSchema(Collections.singletonList(
                new FieldSchema("name", LogicalType.STRING, true, 20, null, null))), TargetMode.APPEND);
    }

    @Test(expected = SchemaLoomException.class)
    public void rejectsRequiredExtraTargetColumn() {
        TargetFixture fixture = TargetFixture.existing(
                column("id", Types.INTEGER, 10, 0, "NO", null, "NO", "NO"),
                column("required", Types.VARCHAR, 10, 0, "NO", null, "NO", "NO"));
        fixture.target().prepare(new RecordSchema(Collections.singletonList(
                new FieldSchema("id", LogicalType.INT32, false, null, null, null))), TargetMode.APPEND);
    }

    @Test public void replaceDropsAndCreatesExistingTable() {
        TargetFixture fixture = TargetFixture.existing(
                column("old_value", Types.VARCHAR, 10, 0, "YES", null, "NO", "NO"));
        fixture.target().prepare(new RecordSchema(Collections.singletonList(
                new FieldSchema("id", LogicalType.INT32, false, null, null, null))), TargetMode.REPLACE);
        assertEquals(2, fixture.sql.size());
        assertTrue(fixture.sql.get(0).startsWith("DROP TABLE"));
        assertTrue(fixture.sql.get(1).startsWith("CREATE TABLE"));
    }

    private static Map<String, Object> column(String name, int type, int size, int scale, String nullable,
                                               String definition, String autoIncrement, String generated) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("COLUMN_NAME", name); row.put("DATA_TYPE", type); row.put("COLUMN_SIZE", size);
        row.put("DECIMAL_DIGITS", scale); row.put("IS_NULLABLE", nullable); row.put("COLUMN_DEF", definition);
        row.put("IS_AUTOINCREMENT", autoIncrement); row.put("IS_GENERATEDCOLUMN", generated);
        return row;
    }

    private static final class TargetFixture {
        final List<String> sql = new ArrayList<String>();
        final List<Map<String, Object>> columns;

        private TargetFixture(List<Map<String, Object>> columns) { this.columns = columns; }

        static TargetFixture existing(Map<String, Object>... columns) {
            return new TargetFixture(Arrays.asList(columns));
        }

        JdbcTableTarget target() {
            final DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("getTables".equals(method.getName())) return resultSet(Collections.singletonList(Collections.<String, Object>emptyMap()));
                            if ("getColumns".equals(method.getName())) return resultSet(columns);
                            return defaultValue(method.getReturnType());
                        }
                    });
            final Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Statement.class}, new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("executeUpdate".equals(method.getName())) { sql.add(String.valueOf(args[0])); return 0; }
                            return defaultValue(method.getReturnType());
                        }
                    });
            final Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("getMetaData".equals(method.getName())) return metadata;
                            if ("createStatement".equals(method.getName())) return statement;
                            return defaultValue(method.getReturnType());
                        }
                    });
            return new JdbcTableTarget(new ConnectionProvider() {
                public Connection getConnection() { return connection; }
                public void close() { }
            }, "orders", DatabaseType.MYSQL);
        }
    }

    private static ResultSet resultSet(final List<Map<String, Object>> rows) {
        return (ResultSet) Proxy.newProxyInstance(JdbcTableTargetContractTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, new InvocationHandler() {
                    int index = -1;
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("next".equals(method.getName())) return ++index < rows.size();
                        if ("close".equals(method.getName())) return null;
                        if (method.getName().startsWith("get")) {
                            Object value = rows.get(index).get(String.valueOf(args[0]));
                            if (method.getName().equals("getString")) return value == null ? null : String.valueOf(value);
                            if (method.getName().equals("getInt")) return value == null ? 0 : ((Number) value).intValue();
                            if (method.getName().equals("getBoolean")) return value != null && "YES".equalsIgnoreCase(String.valueOf(value));
                        }
                        return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
