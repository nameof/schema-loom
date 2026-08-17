package io.github.nameof.schemaloom.metadata;

import java.util.*;

public final class ForeignKeyInfo {
    private final String name, updateRule, deleteRule;
    private final QualifiedTableName referencedTable;
    private final List<String> columns, referencedColumns;
    public ForeignKeyInfo(String name, QualifiedTableName referencedTable, List<String> columns,
                          List<String> referencedColumns, String updateRule, String deleteRule) {
        this.name = name; this.referencedTable = referencedTable; this.updateRule = updateRule; this.deleteRule = deleteRule;
        this.columns = Collections.unmodifiableList(new ArrayList<String>(columns));
        this.referencedColumns = Collections.unmodifiableList(new ArrayList<String>(referencedColumns));
    }
    public String getName() { return name; } public QualifiedTableName getReferencedTable() { return referencedTable; }
    public List<String> getColumns() { return columns; } public List<String> getReferencedColumns() { return referencedColumns; }
    public String getUpdateRule() { return updateRule; } public String getDeleteRule() { return deleteRule; }
}
