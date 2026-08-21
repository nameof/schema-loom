package io.github.nameof.schemaloom.target;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.source.XlsxSource;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.time.*;

import static org.junit.Assert.*;

public class XlsxBoundaryTest {
    @Test
    public void replacesReadsUnicodeAndPreservesLeadingZero() throws Exception {
        Path file = Files.createTempFile("schemaloom-xlsx", ".xlsx");
        RecordSchema schema = new RecordSchema(Arrays.asList(FieldSchema.of("code", LogicalType.STRING), FieldSchema.of("name", LogicalType.STRING)));
        XlsxTarget target = new XlsxTarget(file);
        target.prepare(SchemaDescriptor.of(schema), TargetMode.REPLACE);
        target.write(new RecordBatch(schema, Collections.singletonList(new DataRecord(schema, Arrays.<Object>asList("00123", "中文")))));
        target.close();

        XlsxSource source = new XlsxSource(file, "Sheet1", schema);
        final List<DataRecord> records = new ArrayList<DataRecord>();
        source.read(batch -> records.addAll(batch.getRecords()));
        assertEquals("00123", records.get(0).get("code"));
        assertEquals("中文", records.get(0).get("name"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAppendMode() throws Exception {
        Path file = Files.createTempFile("schemaloom-xlsx-append", ".xlsx");
        new XlsxTarget(file).prepare(SchemaDescriptor.of(new RecordSchema(Collections.singletonList(FieldSchema.of("id", LogicalType.INT32)))), TargetMode.APPEND);
    }

    @Test
    public void createsMultipleSheetsAtConfiguredBoundary() throws Exception {
        Path file = Files.createTempFile("schemaloom-xlsx-sheets", ".xlsx");
        RecordSchema schema = new RecordSchema(Collections.singletonList(FieldSchema.of("id", LogicalType.INT32)));
        XlsxTarget target = new XlsxTarget(file, 3);
        target.prepare(SchemaDescriptor.of(schema), TargetMode.REPLACE);
        List<DataRecord> records = new ArrayList<DataRecord>();
        for (int i = 1; i <= 3; i++) records.add(new DataRecord(schema, Collections.<Object>singletonList(i)));
        target.write(new RecordBatch(schema, records));
        target.close();
        Workbook workbook = WorkbookFactory.create(file.toFile());
        try {
            assertEquals(2, workbook.getNumberOfSheets());
            assertEquals("Sheet1", workbook.getSheetAt(0).getSheetName());
            assertEquals("Sheet2", workbook.getSheetAt(1).getSheetName());
            assertEquals(3, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals(2, workbook.getSheetAt(1).getPhysicalNumberOfRows());
        } finally {
            workbook.close();
        }
    }

    @Test
    public void infersNumericAndUnicodeCells() throws Exception {
        Path file = Files.createTempFile("schemaloom-xlsx-infer", ".xlsx");
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Data");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("amount");
        header.createCell(1).setCellValue("name");
        Row data = sheet.createRow(1);
        data.createCell(0).setCellValue(12.5);
        data.createCell(1).setCellValue("中文");
        OutputStream out = Files.newOutputStream(file);
        try { workbook.write(out); } finally { out.close(); workbook.close(); }
        RecordSchema schema = new XlsxSource(file, "Data", null).schema().getSchema();
        assertEquals(LogicalType.DECIMAL, schema.field("amount").getLogicalType());
        assertEquals(LogicalType.STRING, schema.field("name").getLogicalType());
    }

    @Test
    public void usesConfiguredBatchSize() throws Exception {
        Path file = Files.createTempFile("schemaloom-xlsx-batch", ".xlsx");
        RecordSchema schema = new RecordSchema(Collections.singletonList(FieldSchema.of("id", LogicalType.INT32)));
        XlsxTarget target = new XlsxTarget(file);
        target.prepare(SchemaDescriptor.of(schema), TargetMode.REPLACE);
        List<DataRecord> records = new ArrayList<DataRecord>();
        for (int i = 1; i <= 3; i++) records.add(new DataRecord(schema, Collections.<Object>singletonList(i)));
        target.write(new RecordBatch(schema, records));
        target.close();

        XlsxSource source = new XlsxSource(file, "Sheet1", schema, 2);
        List<Integer> sizes = new ArrayList<Integer>();
        source.read(batch -> sizes.add(batch.getRecords().size()));
        assertEquals(Arrays.asList(2, 1), sizes);
    }

    @Test
    public void encodesLogicalDateAndBinaryValues() throws Exception {
        Path file = Files.createTempFile("schemaloom-xlsx-values", ".xlsx");
        RecordSchema schema = new RecordSchema(Arrays.asList(
                FieldSchema.of("day", LogicalType.DATE),
                FieldSchema.of("created_at", LogicalType.TIMESTAMP),
                FieldSchema.of("payload", LogicalType.BINARY),
                FieldSchema.of("offset_at", LogicalType.OFFSET_TIMESTAMP)));
        XlsxTarget target = new XlsxTarget(file);
        target.prepare(SchemaDescriptor.of(schema), TargetMode.REPLACE);
        target.write(new RecordBatch(schema, Collections.singletonList(new DataRecord(schema, Arrays.<Object>asList(
                LocalDate.of(2026, 8, 13), LocalDateTime.of(2026, 8, 13, 14, 30),
                new byte[]{1, 2, 3}, OffsetDateTime.parse("2026-08-13T14:30:00+08:00"))))));
        target.close();

        XlsxSource source = new XlsxSource(file, "Sheet1", schema);
        final List<DataRecord> records = new ArrayList<DataRecord>();
        source.read(batch -> records.addAll(batch.getRecords()));
        assertEquals(LocalDate.of(2026, 8, 13), records.get(0).get(0));
        assertEquals(LocalDateTime.of(2026, 8, 13, 14, 30), records.get(0).get(1));
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) records.get(0).get(2));
        assertEquals(OffsetDateTime.parse("2026-08-13T14:30+08:00"), records.get(0).get(3));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveBatchSize() throws Exception {
        new XlsxSource(Files.createTempFile("schemaloom-xlsx-batch", ".xlsx"), "Sheet1", null, 0);
    }
}
