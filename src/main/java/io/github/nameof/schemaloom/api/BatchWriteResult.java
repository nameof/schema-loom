package io.github.nameof.schemaloom.api;

public final class BatchWriteResult {
    private final int written;
    private final int failed;

    public BatchWriteResult(int written, int failed) {
        if (written < 0 || failed < 0) throw new IllegalArgumentException();
        this.written = written;
        this.failed = failed;
    }

    public int getWritten() {
        return written;
    }

    public int getFailed() {
        return failed;
    }
}
