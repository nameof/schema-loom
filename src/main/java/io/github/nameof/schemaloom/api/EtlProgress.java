package io.github.nameof.schemaloom.api;

import java.time.Instant;

/** Immutable progress snapshot emitted after a source batch is processed. */
public final class EtlProgress {
    private final long total;
    private final long batchIndex;
    private final long read, transformed, filtered, written, failed;
    private final Instant started;

    public EtlProgress(long total, long batchIndex, long read, long transformed,
                       long filtered, long written, long failed, Instant started) {
        this.total = total;
        this.batchIndex = batchIndex;
        this.read = read;
        this.transformed = transformed;
        this.filtered = filtered;
        this.written = written;
        this.failed = failed;
        this.started = started;
    }

    public long getTotal() { return total; }
    public long getBatchIndex() { return batchIndex; }
    public long getRead() { return read; }
    public long getTransformed() { return transformed; }
    public long getFiltered() { return filtered; }
    public long getWritten() { return written; }
    public long getFailed() { return failed; }
    public Instant getStarted() { return started; }
}
