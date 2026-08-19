package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.DatabaseType;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;

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
        assertTrue(new DialectRegistry().get(DatabaseType.SQL_SERVER).createTable("\"t\"", s).contains("PRIMARY KEY"));
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
}
