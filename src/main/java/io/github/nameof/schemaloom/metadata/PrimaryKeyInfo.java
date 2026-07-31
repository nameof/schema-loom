package io.github.nameof.schemaloom.metadata;

import java.util.*;

public final class PrimaryKeyInfo {
    private final String name; private final List<String> columns;
    public PrimaryKeyInfo(String name, List<String> columns) { this.name = name; this.columns = Collections.unmodifiableList(new ArrayList<String>(columns)); }
    public String getName() { return name; } public List<String> getColumns() { return columns; }
}
