package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.DatabaseType;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.metadata.ColumnInfo;
import io.github.nameof.schemaloom.metadata.TableInfo;

import java.util.*;

import org.junit.Test;

import static org.junit.Assert.*;

public class DialectTest {
    @Test
    public void quotesAndCreatesPrimaryKey() {
        RecordSchema s = new RecordSchema(Arrays.asList(new FieldSchema("id", LogicalType.INT32, false, null, null, null)), Collections.singletonList("id"));
        assertEquals("`we``ird`", new DialectRegistry().get(DatabaseType.MYSQL).quote("we`ird"));
        assertEquals("`shop`.`sales`.`orders`", new DialectRegistry().get(DatabaseType.MYSQL)
                .quote(new QualifiedTableName("shop", "sales", "orders")));
        TableInfo table = new TableInfo(new QualifiedTableName(null, null, "t"), false, s);
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
        assertEquals("TIMESTAMP WITH TIME ZONE", registry.get(DatabaseType.ORACLE)
                .mapping(LogicalType.OFFSET_TIME).getDdlType(FieldSchema.of("time", LogicalType.OFFSET_TIME)));
    }

    @Test
    public void createsNativeViewDdl() {
        DatabaseDialect dialect = new DialectRegistry().get(DatabaseType.MYSQL);
        assertEquals("CREATE VIEW `v` AS SELECT 1", dialect.createView("`v`", "SELECT 1"));
        assertEquals("DROP VIEW `v`", dialect.dropView("`v`"));
    }

    @Test
    public void migratesSafeDefaultsAndDialectAliases() {
        FieldSchema created = new FieldSchema("created", LogicalType.TIMESTAMP, true, null, null, null);
        FieldSchema active = new FieldSchema("active", LogicalType.BOOLEAN, false, null, null, null);
        TableInfo source = new TableInfo(new QualifiedTableName(null, null, "source"), false, "TABLE",
                new RecordSchema(Arrays.asList(created, active)), Arrays.asList(
                new ColumnInfo("created", "TIMESTAMP", null, LogicalType.TIMESTAMP, 1, true, null, null, null, "SYSDATE", null, false, false),
                new ColumnInfo("active", "BOOLEAN", null, LogicalType.BOOLEAN, 2, false, null, null, null, "TRUE", null, false, false)),
                null, Collections.<io.github.nameof.schemaloom.metadata.IndexInfo>emptyList(),
                Collections.<io.github.nameof.schemaloom.metadata.ForeignKeyInfo>emptyList(),
                Collections.<io.github.nameof.schemaloom.metadata.ConstraintInfo>emptyList(), null);
        String oracle = new DialectRegistry().get(DatabaseType.ORACLE).createTableSql("\"t\"", source);
        assertTrue(oracle.contains("DEFAULT CURRENT_TIMESTAMP"));
        assertTrue(oracle.contains("DEFAULT 1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafeDefaultExpression() {
        ColumnInfo column = new ColumnInfo("x", "INT", null, LogicalType.INT32, 1, true, null, null, null,
                "other_column", null, false, false);
        TableInfo source = new TableInfo(new QualifiedTableName(null, null, "source"), false, "TABLE",
                new RecordSchema(Collections.singletonList(FieldSchema.of("x", LogicalType.INT32))),
                Collections.singletonList(column), null, Collections.<io.github.nameof.schemaloom.metadata.IndexInfo>emptyList(),
                Collections.<io.github.nameof.schemaloom.metadata.ForeignKeyInfo>emptyList(),
                Collections.<io.github.nameof.schemaloom.metadata.ConstraintInfo>emptyList(), null);
        new DialectRegistry().get(DatabaseType.MYSQL).createTableSql("`t`", source);
    }

    @Test
    public void quotesUnquotedStringDefaultFromJdbcMetadata() {
        ColumnInfo column = new ColumnInfo("remark", "VARCHAR", null, LogicalType.STRING, 1, true, 32, null, null,
                "remarksssss", null, false, false);
        TableInfo source = new TableInfo(new QualifiedTableName(null, null, "source"), false, "TABLE",
                new RecordSchema(Collections.singletonList(new FieldSchema("remark", LogicalType.STRING, true, 32, null, null))),
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
                new ColumnInfo("id", "INT", null, LogicalType.INT32, 1, false, null, null, null, null, null, true, false),
                new ColumnInfo("total", "INT", null, LogicalType.INT32, 2, true, null, null, null, null, "(id + 1)", false, true));
        TableInfo source = new TableInfo(new QualifiedTableName(null, null, "source"), false, "TABLE",
                new RecordSchema(fields, Collections.singletonList("id")), columns, null,
                Collections.<io.github.nameof.schemaloom.metadata.IndexInfo>emptyList(),
                Collections.<io.github.nameof.schemaloom.metadata.ForeignKeyInfo>emptyList(),
                Collections.<io.github.nameof.schemaloom.metadata.ConstraintInfo>emptyList(), null);
        assertTrue(new DialectRegistry().get(DatabaseType.MYSQL).createTableSql("`t`", source).contains("AUTO_INCREMENT"));
        assertTrue(new DialectRegistry().get(DatabaseType.ORACLE).createTableSql("\"t\"", source).contains("GENERATED BY DEFAULT AS IDENTITY"));
        assertTrue(new DialectRegistry().get(DatabaseType.SQL_SERVER).createTableSql("\"t\"", source).contains("IDENTITY(1,1)"));
        assertTrue(new DialectRegistry().get(DatabaseType.MYSQL).createTableSql("`t`", source).contains("GENERATED ALWAYS AS (id + 1)"));
        assertTrue(new DialectRegistry().get(DatabaseType.SQL_SERVER).createTableSql("\"t\"", source).contains("AS (id + 1)"));
    }
}
