package io.github.nameof.schemaloom.api;

public interface Source extends AutoCloseable {
    RecordSchema schema();

    void read(BatchConsumer consumer);

    @Override
    void close();
}
