package io.github.nameof.schemaloom.api;

public interface Target extends AutoCloseable {
    void prepare(RecordSchema schema, TargetMode mode);

    default void prepare(SchemaDescriptor descriptor, TargetMode mode) {
        if (descriptor == null) throw new IllegalArgumentException("schema descriptor is required");
        prepare(descriptor.getSchema(), mode);
    }

    BatchWriteResult write(RecordBatch batch);

    @Override
    void close();
}
