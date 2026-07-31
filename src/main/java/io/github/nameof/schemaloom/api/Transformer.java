package io.github.nameof.schemaloom.api;

public interface Transformer {
    TransformResult transform(DataRecord record);

    static Transformer identity() {
        return new Transformer() {
            public TransformResult transform(DataRecord r) {
                return TransformResult.keep(r);
            }
        };
    }
}
