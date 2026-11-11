package io.github.nameof.schemaloom.api;

import org.junit.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
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

    @Test public void decodesExcelTimeFromSerialNumber() {
        // Excel 时间单元格常以序列号返回（0.5 = 12:00），此前只有日期列有兜底，时间列会解析失败
        LogicalTypeDefinition time = LogicalTypeCatalog.get(LogicalType.TIME);
        assertEquals(LocalTime.of(12, 0), time.decodeExcel(0.5d));
        assertEquals(LocalTime.of(12, 0), time.decodeExcel("0.5"));
        assertEquals(LocalTime.of(6, 0), time.decodeExcel(0.25d));
        // 纯时间文本仍按原样解析
        assertEquals(LocalTime.of(12, 0), time.decodeExcel("12:00:00"));
    }

    @Test public void coercesOracleDateTimeToDate() {
        // Oracle DATE 在 SchemaCrawler 侧是 DATE、在驱动 ResultSetMetaData 侧是 TIMESTAMP，
        // 收敛时必须把 LocalDateTime 截断成 LocalDate，否则 DataRecord 的值校验直接抛异常
        assertEquals(LocalDate.of(2026, 1, 5),
                LogicalTypeCatalog.coerce(java.time.LocalDateTime.of(2026, 1, 5, 13, 20), LogicalType.DATE));
        // 已是目标类型、null、以及无关类型都不受影响
        assertEquals(LocalDate.of(2026, 1, 5), LogicalTypeCatalog.coerce(LocalDate.of(2026, 1, 5), LogicalType.DATE));
        assertNull(LogicalTypeCatalog.coerce(null, LogicalType.DATE));
        assertEquals(java.time.LocalDateTime.of(2026, 1, 5, 13, 20),
                LogicalTypeCatalog.coerce(java.time.LocalDateTime.of(2026, 1, 5, 13, 20), LogicalType.TIMESTAMP));
        // 复现原始崩溃路径：收敛后的值必须能通过 Schema 校验
        RecordSchema schema = new RecordSchema(Arrays.asList(FieldSchema.of("created_at", LogicalType.DATE)));
        Object coerced = LogicalTypeCatalog.coerce(java.time.LocalDateTime.of(2026, 1, 5, 13, 20), schema.getFields().get(0).getLogicalType());
        assertEquals(LocalDate.of(2026, 1, 5), new DataRecord(schema, Arrays.asList(coerced)).get(0));
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
