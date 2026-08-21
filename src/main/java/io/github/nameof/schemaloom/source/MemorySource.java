package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;

import java.util.*;

public final class MemorySource implements Source {
    private final RecordSchema schema;
    private final List<DataRecord> records;
    private final int batchSize;

    public MemorySource(RecordSchema schema, List<DataRecord> records, int batchSize) {
        this.schema = schema;
        this.records = Collections.unmodifiableList(new ArrayList<DataRecord>(records));
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        this.batchSize = batchSize;
    }

    public SchemaDescriptor schema() {
        return SchemaDescriptor.of(schema);
    }

    public long count() {
        return records.size();
    }

    public void read(BatchConsumer consumer) {
        for (int i = 0; i < records.size(); i += batchSize) {
            if (Thread.currentThread().isInterrupted()) throw new SchemaLoomException("interrupted");
            consumer.accept(new RecordBatch(schema, records.subList(i, Math.min(records.size(), i + batchSize))));
        }
    }

    public void close() {
    }
}
