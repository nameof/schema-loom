package io.github.nameof.schemaloom.api;

import lombok.ToString;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@ToString
public final class EtlResult {
    private final EtlStatus status;
    private final long read, transformed, filtered, written, failed;
    private final Instant started, ended;
    private final List<EtlError> errors;

    public EtlResult(EtlStatus status, long read, long transformed, long filtered, long written, long failed, Instant started, Instant ended, List<EtlError> errors) {
        this.status = status;
        this.read = read;
        this.transformed = transformed;
        this.filtered = filtered;
        this.written = written;
        this.failed = failed;
        this.started = started;
        this.ended = ended;
        this.errors = Collections.unmodifiableList(new ArrayList<EtlError>(errors));
    }

    public EtlStatus getStatus() {
        return status;
    }

    public long getRead() {
        return read;
    }

    public long getTransformed() {
        return transformed;
    }

    public long getFiltered() {
        return filtered;
    }

    public long getWritten() {
        return written;
    }

    public long getFailed() {
        return failed;
    }

    public Instant getStarted() {
        return started;
    }

    public Instant getEnded() {
        return ended;
    }

    public long getElapsedMillis() {
        return Duration.between(started, ended).toMillis();
    }

    public List<EtlError> getErrors() {
        return errors;
    }

}
