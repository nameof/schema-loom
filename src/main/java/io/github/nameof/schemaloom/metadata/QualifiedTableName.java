package io.github.nameof.schemaloom.metadata;

public final class QualifiedTableName {
    private final String catalog, schema, table;

    public QualifiedTableName(String catalog, String schema, String table) {
        if (table == null || table.trim().isEmpty()) throw new IllegalArgumentException("table is blank");
        this.catalog = catalog;
        this.schema = schema;
        this.table = table;
    }

    public String getCatalog() {
        return catalog;
    }

    public String getSchema() {
        return schema;
    }

    public String getTable() {
        return table;
    }
}
