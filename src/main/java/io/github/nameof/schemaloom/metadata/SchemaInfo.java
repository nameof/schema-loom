package io.github.nameof.schemaloom.metadata;

public final class SchemaInfo {
    private final String catalog, name;
    public SchemaInfo(String catalog, String name) { this.catalog = catalog; this.name = name; }
    public String getCatalog() { return catalog; }
    public String getName() { return name; }
}
