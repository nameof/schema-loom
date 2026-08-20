package io.github.nameof.schemaloom.api;

import io.github.nameof.schemaloom.metadata.TableInfo;

/** Source schema plus optional structural metadata used by schema-aware targets. */
public final class SchemaDescriptor {
    private final RecordSchema schema;
    private final TableInfo tableInfo;

    public SchemaDescriptor(RecordSchema schema) {
        this(schema, null);
    }

    public SchemaDescriptor(RecordSchema schema, TableInfo tableInfo) {
        if (schema == null) throw new IllegalArgumentException("schema is required");
        this.schema = schema;
        this.tableInfo = tableInfo;
    }

    public RecordSchema getSchema() { return schema; }
    public TableInfo getTableInfo() { return tableInfo; }
}
