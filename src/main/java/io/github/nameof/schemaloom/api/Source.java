package io.github.nameof.schemaloom.api;

public interface Source extends AutoCloseable {
    SchemaDescriptor schema();

    /**
     * Returns the observed source row count, or -1 when an exact count is not available.
     */
    default long count() {
        return -1L;
    }

    void read(BatchConsumer consumer);

    @Override
    void close();
}
