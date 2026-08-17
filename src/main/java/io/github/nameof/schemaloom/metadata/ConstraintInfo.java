package io.github.nameof.schemaloom.metadata;

import java.util.*;

public final class ConstraintInfo {
    private final String name, type;
    private final List<String> columns;
    public ConstraintInfo(String name, String type, List<String> columns) {
        this.name = name; this.type = type; this.columns = Collections.unmodifiableList(new ArrayList<String>(columns));
    }
    public String getName() { return name; } public String getType() { return type; } public List<String> getColumns() { return columns; }
}
