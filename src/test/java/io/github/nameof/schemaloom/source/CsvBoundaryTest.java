package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.target.CsvTarget;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

import static org.junit.Assert.*;

public class CsvBoundaryTest {
    @Test(expected = SchemaLoomException.class)
    public void rejectsEmptyFile() throws Exception {
        Path file = Files.createTempFile("schemaloom-empty", ".csv");
        new CsvSource(file).schema();
    }

    @Test(expected = SchemaLoomException.class)
    public void rejectsDuplicateHeaders() throws Exception {
        Path file = Files.createTempFile("schemaloom-duplicate", ".csv");
        Files.write(file, Arrays.asList("id,id", "1,2"), StandardCharsets.UTF_8);
        new CsvSource(file).schema();
    }

    @Test
    public void infersTypesAndPreservesUnicodeAndLeadingZero() throws Exception {
        Path file = Files.createTempFile("schemaloom-infer", ".csv");
        Files.write(file, Arrays.asList("code,name,amount,day", "00123,中文,12.50,2026-08-02"), StandardCharsets.UTF_8);
        CsvSource source = new CsvSource(file);
        RecordSchema schema = source.schema().getSchema();
        assertEquals(LogicalType.STRING, schema.field("code").getLogicalType());
        assertEquals(LogicalType.STRING, schema.field("name").getLogicalType());
        assertEquals(LogicalType.DECIMAL, schema.field("amount").getLogicalType());
        assertEquals(LogicalType.DATE, schema.field("day").getLogicalType());
        final List<DataRecord> records = new ArrayList<DataRecord>();
        source.read(batch -> records.addAll(batch.getRecords()));
        assertEquals("00123", records.get(0).get("code"));
        assertEquals("中文", records.get(0).get("name"));
        assertEquals(LocalDate.of(2026, 8, 2), records.get(0).get("day"));
    }

    @Test
    public void usesConfiguredBatchSize() throws Exception {
        Path file = Files.createTempFile("schemaloom-batch", ".csv");
        Files.write(file, Arrays.asList("id", "1", "2", "3"), StandardCharsets.UTF_8);
        RecordSchema schema = new RecordSchema(Collections.singletonList(FieldSchema.of("id", LogicalType.INT32)));
        CsvSource source = new CsvSource(file, schema, StandardCharsets.UTF_8, ',', 0, 2);
        List<Integer> sizes = new ArrayList<Integer>();
        source.read(batch -> sizes.add(batch.getRecords().size()));
        assertEquals(Arrays.asList(2, 1), sizes);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveBatchSize() throws Exception {
        new CsvSource(Files.createTempFile("schemaloom-batch", ".csv"), null,
                StandardCharsets.UTF_8, ',', 0, 0);
    }

    @Test
    public void supportsReplaceAndAppend() throws Exception {
        Path file = Files.createTempFile("schemaloom-target", ".csv");
        RecordSchema schema = new RecordSchema(Arrays.asList(FieldSchema.of("id", LogicalType.INT32), FieldSchema.of("name", LogicalType.STRING)));
        CsvTarget replace = new CsvTarget(file);
        replace.prepare(SchemaDescriptor.of(schema), TargetMode.REPLACE);
        replace.write(batch(schema, 1, "一"));
        replace.close();
        assertEquals(Arrays.asList("id,name", "1,一"), Files.readAllLines(file, StandardCharsets.UTF_8));

        CsvTarget append = new CsvTarget(file);
        append.prepare(SchemaDescriptor.of(schema), TargetMode.APPEND);
        append.write(batch(schema, 2, "二"));
        append.close();
        assertEquals(Arrays.asList("id,name", "1,一", "2,二"), Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonStandardValueBeforeSerialization() throws Exception {
        Path file = Files.createTempFile("schemaloom-partial", ".csv");
        RecordSchema schema = new RecordSchema(Collections.singletonList(FieldSchema.of("value", LogicalType.STRING)));
        CsvTarget target = new CsvTarget(file);
        target.prepare(SchemaDescriptor.of(schema), TargetMode.REPLACE);
        new DataRecord(schema, Collections.<Object>singletonList(new Object()));
    }

    private static RecordBatch batch(RecordSchema schema, int id, String name) {
        return new RecordBatch(schema, Collections.singletonList(new DataRecord(schema, Arrays.<Object>asList(id, name))));
    }
}
