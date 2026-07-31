package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.api.RecordSchema;

public final class TableInfo {
    private final QualifiedTableName name;
    private final boolean view;
    private final RecordSchema schema;

    public TableInfo(QualifiedTableName name, boolean view, RecordSchema schema) {
        this.name = name;
        this.view = view;
        this.schema = schema;
    }

    public QualifiedTableName getName() {
        return name;
    }

    public boolean isView() {
        return view;
    }

    public RecordSchema getSchema() {
        return schema;
    }
}
