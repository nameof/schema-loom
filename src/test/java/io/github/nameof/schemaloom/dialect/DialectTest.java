package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.DatabaseType;

import java.util.*;

import org.junit.Test;

import static org.junit.Assert.*;

public class DialectTest {
    @Test
    public void quotesAndCreatesPrimaryKey() {
        RecordSchema s = new RecordSchema(Arrays.asList(new FieldSchema("id", LogicalType.INT32, false, null, null, null)), Collections.singletonList("id"));
        assertEquals("`we``ird`", new DialectRegistry().get(DatabaseType.MYSQL).quote("we`ird"));
        assertTrue(new DialectRegistry().get(DatabaseType.SQL_SERVER).createTable("\"t\"", s).contains("PRIMARY KEY"));
    }
}
