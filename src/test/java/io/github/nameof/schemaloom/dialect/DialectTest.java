package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.DatabaseType;
import io.github.nameof.schemaloom.metadata.*;

import java.util.*;
import java.sql.Types;

import org.junit.Test;

import static org.junit.Assert.*;

public class DialectTest {
    @Test
    public void quotesAndCreatesPrimaryKey() {
        RecordSchema s = new RecordSchema(Arrays.asList(new FieldSchema("id", LogicalType.INT32, false, null, null, null)));
        assertEquals("`we``ird`", new DialectRegistry().get(DatabaseType.MYSQL).quote("we`ird"));
        assertEquals("`shop`.`sales`.`orders`", new DialectRegistry().get(DatabaseType.MYSQL)
                .quote(new QualifiedTableName("shop", "sales", "orders")));
        TableInfo table = new TableInfo(new QualifiedTableName(null, null, "t"), false, "TABLE",
                Collections.singletonList(new ColumnInfo("id", "INT", null, LogicalType.INT32,
                        Types.INTEGER, 1, false, null, null, null)),
                new PrimaryKeyInfo(null, Collections.singletonList("id")),
                Collections.<IndexInfo>emptyList(), Collections.<ForeignKeyInfo>emptyList(),
                Collections.<ConstraintInfo>emptyList(), null);
        assertTrue(new DialectRegistry().get(DatabaseType.SQL_SERVER).createTableSql("\"t\"", table).contains("PRIMARY KEY"));
    }

    @Test
    public void declaresEveryLogicalTypeCapability() {
        DialectRegistry registry = new DialectRegistry();
        for (DatabaseType database : DatabaseType.values())
            for (LogicalType type : LogicalType.values())
                assertNotNull(registry.get(database).mapping(type));
    }

    @Test
    public void mappingResolvesFieldSpecificDdlType() {
        DialectRegistry registry = new DialectRegistry();
        FieldSchema string = new FieldSchema("name", LogicalType.STRING, true, 100, null, null);
        FieldSchema decimal = new FieldSchema("amount", LogicalType.DECIMAL, true, null, 12, 2);

        assertEquals("VARCHAR(100)", registry.get(DatabaseType.MYSQL)
                .mapping(LogicalType.STRING).getDdlType(string));
        assertEquals("DECIMAL(12,2)", registry.get(DatabaseType.SQL_SERVER)
                .mapping(LogicalType.DECIMAL).getDdlType(decimal));
        assertEquals("BYTEA", registry.get(DatabaseType.POSTGRESQL).mapping(LogicalType.BINARY).getDdlType(FieldSchema.of("payload", LogicalType.BINARY)));
        assertEquals("BLOB", registry.get(DatabaseType.DB2).mapping(LogicalType.BINARY).getDdlType(FieldSchema.of("payload", LogicalType.BINARY)));
        assertEquals("DOUBLE", registry.get(DatabaseType.DB2).mapping(LogicalType.FLOAT64).getDdlType(FieldSchema.of("v", LogicalType.FLOAT64)));
        assertEquals("TIMESTAMP WITH TIME ZONE", registry.get(DatabaseType.ORACLE)
                .mapping(LogicalType.OFFSET_TIME).getDdlType(FieldSchema.of("time", LogicalType.OFFSET_TIME)));
    }

    @Test
    public void createsNativeViewDdl() {
        DatabaseDialect dialect = new DialectRegistry().get(DatabaseType.MYSQL);
        assertEquals("CREATE VIEW `v` AS SELECT 1", dialect.createViewSql("`v`", "SELECT 1"));
        assertEquals("DROP VIEW `v`", dialect.dropViewSql("`v`"));
    }

    @Test
    public void createsNativeRenameTableDdl() {
        DialectRegistry registry = new DialectRegistry();
        assertEquals("ALTER TABLE `old` RENAME TO `new`",
                registry.get(DatabaseType.MYSQL).renameTableSql("`old`", "`new`"));
        assertEquals("ALTER TABLE \"old\" RENAME TO \"new\"",
                registry.get(DatabaseType.ORACLE).renameTableSql("\"old\"", "\"new\""));
        assertEquals("RENAME TABLE \"old\" TO \"NEW\"",
                registry.get(DatabaseType.DB2).renameTableSql("\"old\"", "\"new\""));
        assertEquals("EXEC sp_rename N'old', N'new'",
                registry.get(DatabaseType.SQL_SERVER).renameTableSql("\"old\"", "\"new\""));
    }

    @Test
    public void migratesSafeDefaultsAndDialectAliases() {
        FieldSchema created = new FieldSchema("created", LogicalType.TIMESTAMP, true, null, null, null);
        FieldSchema active = new FieldSchema("active", LogicalType.BOOLEAN, false, null, null, null);
        TableInfo source = new TableInfo(new QualifiedTableName(null, null, "source"), false, "TABLE", Arrays.asList(
                new ColumnInfo("created", "TIMESTAMP", null, LogicalType.TIMESTAMP, Types.TIMESTAMP, 1, true, null, null, null, "SYSDATE", null, false, false),
                new ColumnInfo("active", "BOOLEAN", null, LogicalType.BOOLEAN, Types.BOOLEAN, 2, false, null, null, null, "TRUE", null, false, false)),
                null, Collections.<io.github.nameof.schemaloom.metadata.IndexInfo>emptyList(),
                Collections.<io.github.nameof.schemaloom.metadata.ForeignKeyInfo>emptyList(),
                Collections.<io.github.nameof.schemaloom.metadata.ConstraintInfo>emptyList(), null);
        String oracle = new DialectRegistry().get(DatabaseType.ORACLE).createTableSql("\"t\"", source);
        assertTrue(oracle.contains("DEFAULT CURRENT_TIMESTAMP"));
        assertTrue(oracle.contains("DEFAULT 1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafeDefaultExpression() {
        ColumnInfo column = new ColumnInfo("x", "INT", null, LogicalType.INT32, Types.INTEGER, 1, true, null, null, null,
                "other_column", null, false, false);
        TableInfo source = new TableInfo(new QualifiedTableName(null, null, "source"), false, "TABLE",
                Collections.singletonList(column), null, Collections.<io.github.nameof.schemaloom.metadata.IndexInfo>emptyList(),
                Collections.<io.github.nameof.schemaloom.metadata.ForeignKeyInfo>emptyList(),
                Collections.<io.github.nameof.schemaloom.metadata.ConstraintInfo>emptyList(), null);
        new DialectRegistry().get(DatabaseType.MYSQL).createTableSql("`t`", source);
    }

    @Test
    public void quotesUnquotedStringDefaultFromJdbcMetadata() {
        ColumnInfo column = new ColumnInfo("remark", "VARCHAR", null, LogicalType.STRING, Types.VARCHAR, 1, true, 32, null, null,
                "remarksssss", null, false, false);
        TableInfo source = new TableInfo(new QualifiedTableName(null, null, "source"), false, "TABLE",
                Collections.singletonList(column), null, Collections.<io.github.nameof.schemaloom.metadata.IndexInfo>emptyList(),
                Collections.<io.github.nameof.schemaloom.metadata.ForeignKeyInfo>emptyList(),
                Collections.<io.github.nameof.schemaloom.metadata.ConstraintInfo>emptyList(), null);
        assertTrue(new DialectRegistry().get(DatabaseType.MYSQL).createTableSql("`t`", source)
                .contains("DEFAULT 'remarksssss'"));
    }

    @Test
    public void rendersIdentityAndGeneratedColumnsPerDialect() {
        List<FieldSchema> fields = Arrays.asList(
                new FieldSchema("id", LogicalType.INT32, false, null, null, null),
                new FieldSchema("total", LogicalType.INT32, true, null, null, null));
        List<ColumnInfo> columns = Arrays.asList(
                new ColumnInfo("id", "INT", null, LogicalType.INT32, Types.INTEGER, 1, false, null, null, null, null, null, true, false),
                new ColumnInfo("total", "INT", null, LogicalType.INT32, Types.INTEGER, 2, true, null, null, null, null, "(id + 1)", false, true));
        TableInfo source = new TableInfo(new QualifiedTableName(null, null, "source"), false, "TABLE",
                columns, new PrimaryKeyInfo(null, Collections.singletonList("id")),
                Collections.<io.github.nameof.schemaloom.metadata.IndexInfo>emptyList(),
                Collections.<io.github.nameof.schemaloom.metadata.ForeignKeyInfo>emptyList(),
                Collections.<io.github.nameof.schemaloom.metadata.ConstraintInfo>emptyList(), null);
        assertTrue(new DialectRegistry().get(DatabaseType.MYSQL).createTableSql("`t`", source).contains("AUTO_INCREMENT"));
        assertTrue(new DialectRegistry().get(DatabaseType.ORACLE).createTableSql("\"t\"", source).contains("GENERATED BY DEFAULT AS IDENTITY"));
        assertTrue(new DialectRegistry().get(DatabaseType.DB2).createTableSql("\"t\"", source).contains("GENERATED BY DEFAULT AS IDENTITY"));
        assertTrue(new DialectRegistry().get(DatabaseType.SQL_SERVER).createTableSql("\"t\"", source).contains("IDENTITY(1,1)"));
        assertTrue(new DialectRegistry().get(DatabaseType.DM).createTableSql("\"t\"", source).contains("IDENTITY(1,1)"));
        assertTrue(new DialectRegistry().get(DatabaseType.MYSQL).createTableSql("`t`", source).contains("GENERATED ALWAYS AS (id + 1)"));
        assertTrue(new DialectRegistry().get(DatabaseType.DB2).createTableSql("`t`", source).contains("GENERATED ALWAYS AS (id + 1)"));
        assertTrue(new DialectRegistry().get(DatabaseType.DM).createTableSql("\"t\"", source).contains("GENERATED ALWAYS AS (id + 1)"));
        String sqlServer = new DialectRegistry().get(DatabaseType.SQL_SERVER).createTableSql("\"t\"", source);
        assertTrue(sqlServer.contains("\"total\" AS (id + 1)"));
        assertFalse(sqlServer.contains("\"total\" INT AS"));
        assertEquals("SET IDENTITY_INSERT \"t\" ON", new DialectRegistry().get(DatabaseType.SQL_SERVER).identityInsertOnSql("\"t\""));
        assertEquals("SET IDENTITY_INSERT \"t\" OFF", new DialectRegistry().get(DatabaseType.SQL_SERVER).identityInsertOffSql("\"t\""));
    }

    @Test
    public void rendersOrderedNormalAndUniqueIndexes() {
        List<io.github.nameof.schemaloom.metadata.IndexInfo> indexes = Arrays.asList(
                new io.github.nameof.schemaloom.metadata.IndexInfo("ix_normal", false, Arrays.asList("last_name", "first_name")),
                new io.github.nameof.schemaloom.metadata.IndexInfo("ux_email", true, Collections.singletonList("email")));
        List<ColumnInfo> columns = Arrays.asList(
                new ColumnInfo("last_name", "VARCHAR", null, LogicalType.STRING, Types.VARCHAR, 1, true, null, null, null),
                new ColumnInfo("first_name", "VARCHAR", null, LogicalType.STRING, Types.VARCHAR, 2, true, null, null, null),
                new ColumnInfo("email", "VARCHAR", null, LogicalType.STRING, Types.VARCHAR, 3, true, null, null, null));
        TableInfo source = new TableInfo(new QualifiedTableName(null, null, "source"), false, columns, null, indexes, null);
        List<String> sql = new DialectRegistry().get(DatabaseType.MYSQL).indexSql("`t`", indexes);
        assertTrue(sql.get(0).contains("INDEX `ix_normal` ON `t` (`last_name`, `first_name`)") );
        assertTrue(sql.get(1).contains("UNIQUE INDEX `ux_email`") );
    }
    @Test
    public void quotesQualifiedAndReservedIdentifiersForEveryDialect() {
        DialectRegistry registry = new DialectRegistry();
        for (DatabaseType type : DatabaseType.values()) {
            DatabaseDialect dialect = registry.get(type);
            String quote = (type == DatabaseType.MYSQL || type == DatabaseType.MARIADB) ? "`" : "\"";
            // DB2/DM 将标识符转为大写再加引号
            boolean uppercasesIdentifiers = type == DatabaseType.DB2 || type == DatabaseType.DM;
            String expectedSelect = uppercasesIdentifiers ? (quote + "SELECT" + quote) : (quote + "select" + quote);
            assertEquals(expectedSelect, dialect.quote("select"));
            // DB2/DM 的 qualified name 也会大写
            String expectedQualified = uppercasesIdentifiers
                    ? (quote + "CAT" + quote + "." + quote + "APP" + quote + "." + quote + "ORDER" + quote)
                    : (quote + "cat" + quote + "." + quote + "app" + quote + "." + quote + "order" + quote);
            assertEquals(expectedQualified, dialect.quote(new QualifiedTableName("cat", "app", "order")));
            // DB2/DM 的转义引号也会大写
            String expectedEscaped = uppercasesIdentifiers ? (quote + "A" + quote + quote + "B" + quote) : (quote + "a" + quote + quote + "b" + quote);
            assertEquals(expectedEscaped, dialect.quote("a" + quote + "b"));
            // DB2/DM 的 DROP TABLE 也会大写
            String expectedDrop = uppercasesIdentifiers ? ("DROP TABLE " + quote + "ORDER" + quote) : ("DROP TABLE " + quote + "order" + quote);
            assertEquals(expectedDrop, dialect.dropTableSql(dialect.quote("order")));
        }
    }

    @Test
    public void renamesQualifiedTablesAccordingToDatabaseSyntax() {
        DialectRegistry registry = new DialectRegistry();
        assertEquals("ALTER TABLE \"public\".\"old\" RENAME TO \"new\"",
                registry.get(DatabaseType.POSTGRESQL).renameTableSql("\"public\".\"old\"", "\"public\".\"new\""));
        assertEquals("RENAME TABLE \"DB2SCHEMA\".\"old\" TO \"NEW\"",
                registry.get(DatabaseType.DB2).renameTableSql("\"DB2SCHEMA\".\"old\"", "\"DB2SCHEMA\".\"new\""));
        assertEquals("ALTER TABLE \"OLD\" RENAME TO \"NEW\"",
                registry.get(DatabaseType.ORACLE).renameTableSql("\"OLD\"", "\"NEW\""));
        assertEquals("ALTER TABLE \"OLD\" RENAME TO \"NEW\"",
                registry.get(DatabaseType.DM).renameTableSql("\"old\"", "\"new\""));
        assertEquals("EXEC sp_rename N'dbo.old', N'new'",
                registry.get(DatabaseType.SQL_SERVER).renameTableSql("\"dbo\".\"old\"", "\"dbo\".\"new\""));
    }

    @Test
    public void mapsStringDecimalAndBinaryBoundaries() {
        DialectRegistry registry = new DialectRegistry();
        FieldSchema shortText = new FieldSchema("v", LogicalType.STRING, true, 255, null, null);
        FieldSchema longText = new FieldSchema("v", LogicalType.STRING, true, 256, null, null);
        FieldSchema decimal = new FieldSchema("v", LogicalType.DECIMAL, true, null, 12, 2);
        FieldSchema binary = new FieldSchema("v", LogicalType.BINARY, true, 8000, null, null);
        assertEquals("VARCHAR(255)", registry.get(DatabaseType.MYSQL).mapping(LogicalType.STRING).getDdlType(shortText));
        assertEquals("TEXT", registry.get(DatabaseType.MYSQL).mapping(LogicalType.STRING).getDdlType(longText));
        assertEquals("VARCHAR(256)", registry.get(DatabaseType.POSTGRESQL).mapping(LogicalType.STRING).getDdlType(longText));
        assertEquals("VARCHAR(256)", registry.get(DatabaseType.DB2).mapping(LogicalType.STRING).getDdlType(longText));
        assertEquals("VARCHAR(256)", registry.get(DatabaseType.DM).mapping(LogicalType.STRING).getDdlType(longText));
        assertEquals("NVARCHAR(256)", registry.get(DatabaseType.SQL_SERVER).mapping(LogicalType.STRING).getDdlType(longText));
        assertEquals("VARCHAR2(256)", registry.get(DatabaseType.ORACLE).mapping(LogicalType.STRING).getDdlType(longText));
        assertEquals("VARBINARY(8000)", registry.get(DatabaseType.SQL_SERVER).mapping(LogicalType.BINARY).getDdlType(binary));
        assertEquals("DECIMAL(12,2)", registry.get(DatabaseType.MYSQL).mapping(LogicalType.DECIMAL).getDdlType(decimal));
        assertEquals("DECIMAL(12,2)", registry.get(DatabaseType.DB2).mapping(LogicalType.DECIMAL).getDdlType(decimal));
        assertEquals("DECIMAL(12,2)", registry.get(DatabaseType.DM).mapping(LogicalType.DECIMAL).getDdlType(decimal));
        assertEquals("NUMERIC(12,2)", registry.get(DatabaseType.POSTGRESQL).mapping(LogicalType.DECIMAL).getDdlType(decimal));
        assertEquals("NUMBER(12,2)", registry.get(DatabaseType.ORACLE).mapping(LogicalType.DECIMAL).getDdlType(decimal));
    }

    @Test
    public void bindsViewNamespaceAsParameters() {
        DialectRegistry registry = new DialectRegistry();
        io.github.nameof.schemaloom.driver.DatabaseConnectionInfo mysql =
                new io.github.nameof.schemaloom.driver.DatabaseConnectionInfo(DatabaseType.MYSQL, "host", 3306, "shop", "user", "password");
        ViewDefinitionQuery mySqlQuery = registry.get(DatabaseType.MYSQL).viewDefinitionQuery(mysql,
                new QualifiedTableName(null, null, "orders_view"));
        assertEquals(Arrays.<Object>asList("shop", "orders_view"), mySqlQuery.getParameters());
        assertTrue(mySqlQuery.getSql().contains("TABLE_SCHEMA = ?"));

        io.github.nameof.schemaloom.driver.DatabaseConnectionInfo postgresql =
                new io.github.nameof.schemaloom.driver.DatabaseConnectionInfo(DatabaseType.POSTGRESQL, "host", 5432, "db", "user", "password");
        assertEquals(Collections.<Object>singletonList("public.orders_view"), registry.get(DatabaseType.POSTGRESQL)
                .viewDefinitionQuery(postgresql, new QualifiedTableName(null, null, "orders_view")).getParameters());

        io.github.nameof.schemaloom.driver.DatabaseConnectionInfo oracle =
                new io.github.nameof.schemaloom.driver.DatabaseConnectionInfo(DatabaseType.ORACLE, "host", 1521,
                        "db", null, "APP", "APP", "password", null, null);
        assertEquals(Arrays.<Object>asList("APP", "ORDERS_VIEW"), registry.get(DatabaseType.ORACLE)
                .viewDefinitionQuery(oracle, new QualifiedTableName(null, null, "orders_view")).getParameters());
        ViewDefinitionQuery sqlServerQuery = registry.get(DatabaseType.SQL_SERVER)
                .viewDefinitionQuery(oracle, new QualifiedTableName(null, "dbo", "orders_view"));
        assertEquals(Collections.<Object>singletonList("dbo.orders_view"), sqlServerQuery.getParameters());
        assertTrue(sqlServerQuery.getSql().contains("SUBSTRING(definition"));
        assertTrue(sqlServerQuery.getSql().contains("CHARINDEX(' AS ', definition)"));

        io.github.nameof.schemaloom.driver.DatabaseConnectionInfo db2 =
                new io.github.nameof.schemaloom.driver.DatabaseConnectionInfo(DatabaseType.DB2, "host", 50000,
                        "BLUDB", null, "MYSCHEMA", "user", "password", null, null);
        ViewDefinitionQuery db2Query = registry.get(DatabaseType.DB2)
                .viewDefinitionQuery(db2, new QualifiedTableName(null, null, "ORDERS_VIEW"));
        assertEquals(Arrays.<Object>asList("MYSCHEMA", "ORDERS_VIEW"), db2Query.getParameters());
        assertTrue(db2Query.getSql().contains("SYSCAT.VIEWS"));

        io.github.nameof.schemaloom.driver.DatabaseConnectionInfo dm =
                new io.github.nameof.schemaloom.driver.DatabaseConnectionInfo(DatabaseType.DM, "host", 5236,
                        "DAMENG", null, "SCHEMALOOM", "SYSDBA", "password", null, null);
        ViewDefinitionQuery dmQuery = registry.get(DatabaseType.DM)
                .viewDefinitionQuery(dm, new QualifiedTableName(null, null, "ORDERS_VIEW"));
        assertEquals(Arrays.<Object>asList("SCHEMALOOM", "ORDERS_VIEW"), dmQuery.getParameters());
        assertTrue(dmQuery.getSql().contains("ALL_VIEWS"));
    }

    @Test
    public void escapesCommentsAndKeepsIndexColumnOrder() {
        ColumnInfo column = new ColumnInfo("name", "VARCHAR", "owner's name", LogicalType.STRING,
                Types.VARCHAR, 1, true, 30, null, null);
        TableInfo table = new TableInfo(new QualifiedTableName(null, "public", "people"), false, "TABLE",
                Collections.singletonList(column), null, Collections.<IndexInfo>emptyList(),
                Collections.<ForeignKeyInfo>emptyList(), Collections.<ConstraintInfo>emptyList(), "owner's table");
        DatabaseDialect postgres = new DialectRegistry().get(DatabaseType.POSTGRESQL);
        assertEquals("COMMENT ON TABLE \"people\" IS 'owner''s table'", postgres.commentSql("\"people\"", table).get(0));
        assertEquals("COMMENT ON COLUMN \"people\".\"name\" IS 'owner''s name'", postgres.commentSql("\"people\"", table).get(1));
        IndexInfo index = new IndexInfo("ix_name", true, Arrays.asList("last", "first"));
        assertEquals("CREATE UNIQUE INDEX \"ix_name\" ON \"people\" (\"last\", \"first\")",
                postgres.indexSql("\"people\"", Collections.singletonList(index)).get(0));
        assertEquals(1, new DialectRegistry().get(DatabaseType.MYSQL).commentSql("`people`", table).size());
    }
}
