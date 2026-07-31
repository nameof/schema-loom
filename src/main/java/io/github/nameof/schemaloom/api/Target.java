package io.github.nameof.schemaloom.api;

public interface Target extends AutoCloseable {
    void prepare(RecordSchema schema, TargetMode mode);

    BatchWriteResult write(RecordBatch batch);

    @Override
    void close();
}
