package io.github.nameof.schemaloom.api;

import org.junit.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import static org.junit.Assert.*;

public class LogicalTypeCatalogTest {
    @Test public void definesACompleteDefinitionForEveryLogicalType() {
        assertEquals(LogicalType.values().length, LogicalTypeCatalog.definitions().size());
        for (LogicalType type : LogicalType.values()) {
            LogicalTypeDefinition definition = LogicalTypeCatalog.get(type);
            assertNotNull(definition.javaType());
            assertTrue(definition.jdbcSqlType() != 0);
        }
    }

    @Test public void acceptsStandardJavaTypes() {
        RecordSchema schema = new RecordSchema(Arrays.asList(
                FieldSchema.of("date", LogicalType.DATE),
                FieldSchema.of("amount", LogicalType.DECIMAL)));
        DataRecord record = new DataRecord(schema, Arrays.asList(LocalDate.of(2026, 8, 13), new BigDecimal("1.20")));
        assertEquals(LocalDate.of(2026, 8, 13), record.get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsVendorDateType() {
        RecordSchema schema = new RecordSchema(Arrays.asList(FieldSchema.of("date", LogicalType.DATE)));
        new DataRecord(schema, Arrays.<Object>asList(java.sql.Date.valueOf("2026-08-13")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMismatchedValueType() {
        LogicalTypeCatalog.validateValue(LogicalType.INT32, "not a number");
    }
}
