package io.github.nameof.schemaloom.api;

public interface Target extends AutoCloseable {
    void prepare(SchemaDescriptor schema, TargetMode mode);

    BatchWriteResult write(RecordBatch batch);

    @Override
    void close();
}
