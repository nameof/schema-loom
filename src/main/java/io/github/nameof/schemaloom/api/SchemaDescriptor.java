package io.github.nameof.schemaloom.api;

import io.github.nameof.schemaloom.metadata.TableInfo;

/** Source schema plus optional structural metadata used by schema-aware targets. */
public final class SchemaDescriptor {
    private final RecordSchema schema;
    private final TableInfo tableInfo;

    private SchemaDescriptor(RecordSchema schema, TableInfo tableInfo) {
        this.schema = schema;
        this.tableInfo = tableInfo;
    }

    public static SchemaDescriptor of(RecordSchema schema) {
        if (schema == null) throw new IllegalArgumentException("record schema is required");
        return new SchemaDescriptor(schema, null);
    }

    public static SchemaDescriptor of(TableInfo tableInfo) {
        if (tableInfo == null) throw new IllegalArgumentException("table metadata is required");
        return new SchemaDescriptor(tableInfo.getSchema(), tableInfo);
    }

    public RecordSchema getSchema() { return schema; }
    public TableInfo getTableInfo() { return tableInfo; }
}
