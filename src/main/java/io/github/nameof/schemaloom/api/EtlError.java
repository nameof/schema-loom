package io.github.nameof.schemaloom.api;

import io.github.nameof.schemaloom.internal.LoggingSupport;

public final class EtlError {
    private final long row;
    private final String stage;
    private final String type;
    private final String message;

    public EtlError(long row, String stage, Throwable t) {
        this.row = row;
        this.stage = stage;
        this.type = t.getClass().getName();
        this.message = sanitize(t.getMessage());
    }

    private static String sanitize(String s) {
        return LoggingSupport.message(s);
    }

    public long getRow() {
        return row;
    }

    public String getStage() {
        return stage;
    }

    public String getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }
}
