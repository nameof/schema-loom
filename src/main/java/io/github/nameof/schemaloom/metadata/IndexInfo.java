package io.github.nameof.schemaloom.metadata;

import java.util.*;

public final class IndexInfo {
    private final String name, type; private final boolean unique; private final List<String> columns;
    public IndexInfo(String name, boolean unique, List<String> columns) { this(name, null, unique, columns); }
    public IndexInfo(String name, String type, boolean unique, List<String> columns) { this.name = name; this.type = type; this.unique = unique; this.columns = Collections.unmodifiableList(new ArrayList<String>(columns)); }
    public String getName() { return name; } public boolean isUnique() { return unique; } public List<String> getColumns() { return columns; }
    public String getType() { return type; }
}
