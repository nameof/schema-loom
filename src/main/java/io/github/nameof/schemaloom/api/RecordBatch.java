package io.github.nameof.schemaloom.api;

import java.util.*;

public final class RecordBatch {
    private final RecordSchema schema;
    private final List<DataRecord> records;

    public RecordBatch(RecordSchema schema, List<DataRecord> records) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.records = Collections.unmodifiableList(new ArrayList<DataRecord>(records));
        for (DataRecord r : this.records)
            if (r == null || r.getSchema() != schema) throw new IllegalArgumentException("record schema mismatch");
    }

    public RecordSchema getSchema() {
        return schema;
    }

    public List<DataRecord> getRecords() {
        return records;
    }

    public int size() {
        return records.size();
    }
}
