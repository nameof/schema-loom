package io.github.nameof.schemaloom.target;

import io.github.nameof.schemaloom.api.*;

import java.util.*;

public final class MemoryTarget implements Target {
    private RecordSchema schema;
    private final List<DataRecord> records = new ArrayList<DataRecord>();
    private boolean prepared;

    public void prepare(RecordSchema schema, TargetMode mode) {
        this.schema = schema;
        prepared = true;
        if (mode == TargetMode.REPLACE) records.clear();
    }

    public BatchWriteResult write(RecordBatch batch) {
        if (!prepared || batch.getSchema() != schema) throw new SchemaLoomException("target is not prepared");
        records.addAll(batch.getRecords());
        return new BatchWriteResult(batch.size(), 0);
    }

    public List<DataRecord> getRecords() {
        return Collections.unmodifiableList(records);
    }

    public void close() {
    }
}
