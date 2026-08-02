package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.api.LogicalType;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import org.junit.Test;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

import static org.junit.Assert.*;

public class JdbcMetadataContractTest {
    @Test public void mapsMysqlCatalogAndTableMetadata() {
        assertTable(DatabaseFixture.mysql());
    }

    @Test public void mapsOracleSchemaAndTableMetadata() {
        assertTable(DatabaseFixture.oracle());
    }

    @Test public void mapsSqlServerCatalogSchemaAndTableMetadata() {
        assertTable(DatabaseFixture.sqlServer());
    }

    private void assertTable(DatabaseFixture fixture) {
        DatabaseMetadataService service = new DatabaseMetadataService();
        assertEquals(fixture.catalog == null ? 0 : 1, service.listCatalogs(fixture.provider()).size());
        assertEquals(1, service.listSchemas(fixture.provider()).size());
        TableInfo table = service.getTable(fixture.provider(),
                new QualifiedTableName(fixture.catalog, fixture.schema, "orders"));
        assertEquals(fixture.catalog, table.getName().getCatalog());
        assertEquals(fixture.schema, table.getName().getSchema());
        assertFalse(table.isView());
        assertEquals(Arrays.asList("id", "name"), Arrays.asList(
                table.getSchema().getFields().get(0).getName(), table.getSchema().getFields().get(1).getName()));
        assertEquals(LogicalType.INT32, table.getSchema().getFields().get(0).getLogicalType());
        assertEquals(Collections.singletonList("id"), table.getSchema().getPrimaryKeyFields());
        assertNotNull(table.getPrimaryKey());
        assertEquals("PRIMARY", table.getPrimaryKey().getName());
        assertEquals(1, table.getIndexes().size());
        assertEquals("ix_orders_name", table.getIndexes().get(0).getName());
    }

    private static final class DatabaseFixture {
        final String catalog, schema;
        final DatabaseMetaData metadata;

        private DatabaseFixture(String catalog, String schema) {
            this.catalog = catalog;
            this.schema = schema;
            this.metadata = metadata(catalog, schema);
        }

        static DatabaseFixture mysql() { return new DatabaseFixture("mysql_db", null); }
        static DatabaseFixture oracle() { return new DatabaseFixture(null, "APP"); }
        static DatabaseFixture sqlServer() { return new DatabaseFixture("mssql_db", "dbo"); }

        ConnectionProvider provider() {
            final DatabaseMetaData value = metadata;
            final Connection connection = (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class}, handler("getMetaData", value));
            return new ConnectionProvider() {
                public Connection getConnection() { return connection; }
                public void close() { }
            };
        }

        private static DatabaseMetaData metadata(final String catalog, final String schema) {
            InvocationHandler handler = new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("getCatalogs".equals(name)) return resultSet(catalog == null ? Collections.<Map<String, Object>>emptyList() : rows(row("TABLE_CAT", catalog)));
                    if ("getSchemas".equals(name)) return resultSet(schema == null && catalog == null
                            ? Collections.<Map<String, Object>>emptyList()
                            : rows(row("TABLE_CATALOG", catalog, "TABLE_SCHEM", schema)));
                    if ("getTables".equals(name)) return resultSet(rows(
                            row("TABLE_CAT", catalog, "TABLE_SCHEM", schema, "TABLE_NAME", "orders",
                                    "TABLE_TYPE", "TABLE", "REMARKS", "orders table")));
                    if ("getColumns".equals(name)) return resultSet(rows(
                            row("COLUMN_NAME", "id", "DATA_TYPE", Types.INTEGER, "TYPE_NAME", "INTEGER",
                                    "COLUMN_SIZE", 10, "DECIMAL_DIGITS", 0, "NULLABLE", DatabaseMetaData.columnNoNulls,
                                    "ORDINAL_POSITION", 1, "REMARKS", "identifier"),
                            row("COLUMN_NAME", "name", "DATA_TYPE", Types.VARCHAR, "TYPE_NAME", "VARCHAR",
                                    "COLUMN_SIZE", 100, "DECIMAL_DIGITS", 0, "NULLABLE", DatabaseMetaData.columnNullable,
                                    "ORDINAL_POSITION", 2, "REMARKS", "display name")));
                    if ("getPrimaryKeys".equals(name)) return resultSet(rows(
                            row("PK_NAME", "PRIMARY", "KEY_SEQ", (short) 1, "COLUMN_NAME", "id")));
                    if ("getIndexInfo".equals(name)) return resultSet(rows(
                            row("INDEX_NAME", "ix_orders_name", "COLUMN_NAME", "name", "NON_UNIQUE", true)));
                    if ("getDatabaseProductName".equals(name)) return "FixtureDB";
                    if ("getDatabaseProductVersion".equals(name)) return "1.0";
                    if ("getDriverName".equals(name)) return "FixtureDriver";
                    if ("getDriverVersion".equals(name)) return "1.0";
                    if ("getURL".equals(name)) return "jdbc:fixture:test";
                    return defaultValue(method.getReturnType());
                }
            };
            return (DatabaseMetaData) Proxy.newProxyInstance(
                    JdbcMetadataContractTest.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, handler);
        }

        private static InvocationHandler handler(final String methodName, final Object value) {
            return new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if (methodName.equals(method.getName())) return value;
                    return defaultValue(method.getReturnType());
                }
            };
        }
    }

    private static ResultSet resultSet(final List<Map<String, Object>> rows) {
        InvocationHandler handler = new InvocationHandler() {
            int index = -1;
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("next".equals(name)) return ++index < rows.size();
                if ("close".equals(name)) return null;
                if ("getString".equals(name) || "getInt".equals(name) || "getShort".equals(name) || "getBoolean".equals(name)) {
                    Object value = rows.get(index).get(String.valueOf(args[0]));
                    if ("getString".equals(name)) return value == null ? null : String.valueOf(value);
                    if ("getInt".equals(name)) return value == null ? 0 : ((Number) value).intValue();
                    if ("getShort".equals(name)) return value == null ? (short) 0 : ((Number) value).shortValue();
                    return value != null && (Boolean) value;
                }
                return defaultValue(method.getReturnType());
            }
        };
        return (ResultSet) Proxy.newProxyInstance(JdbcMetadataContractTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, handler);
    }

    private static List<Map<String, Object>> rows(Map<String, Object>... rows) {
        return Arrays.asList(rows);
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new HashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) row.put(String.valueOf(values[i]), values[i + 1]);
        return row;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
