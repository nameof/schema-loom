package io.github.nameof.schemaloom.metadata;

public final class MetadataQuery {
    private final String catalog, schema, tablePattern;

    public MetadataQuery(String catalog, String schema, String tablePattern) {
        this.catalog = catalog;
        this.schema = schema;
        this.tablePattern = tablePattern == null ? "%" : tablePattern;
    }

    public String getCatalog() {
        return catalog;
    }

    public String getSchema() {
        return schema;
    }

    public String getTablePattern() {
        return tablePattern;
    }
}
