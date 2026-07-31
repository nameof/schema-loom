package io.github.nameof.schemaloom.api;

public interface BatchConsumer {
    void accept(RecordBatch batch);
}
