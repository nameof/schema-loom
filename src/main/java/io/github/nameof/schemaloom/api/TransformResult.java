package io.github.nameof.schemaloom.api;

public final class TransformResult {
    private final DataRecord record;
    private final boolean dropped;

    private TransformResult(DataRecord r, boolean d) {
        record = r;
        dropped = d;
    }

    public static TransformResult keep(DataRecord r) {
        return new TransformResult(r, false);
    }

    public static TransformResult drop() {
        return new TransformResult(null, true);
    }

    public boolean isDropped() {
        return dropped;
    }

    public DataRecord getRecord() {
        return record;
    }
}
