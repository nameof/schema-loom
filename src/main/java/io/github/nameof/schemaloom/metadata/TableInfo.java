package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.api.RecordSchema;
import java.util.*;

public final class TableInfo {
    private final QualifiedTableName name;
    private final boolean view;
    private final String type;
    private final RecordSchema schema;
    private final List<ColumnInfo> columns;
    private final PrimaryKeyInfo primaryKey;
    private final List<IndexInfo> indexes;
    private final String remarks;
    private final List<ForeignKeyInfo> foreignKeys;
    private final List<ConstraintInfo> constraints;

    public TableInfo(QualifiedTableName name, boolean view, RecordSchema schema) {
        this(name, view, view ? "VIEW" : "TABLE", schema, Collections.<ColumnInfo>emptyList(), null, Collections.<IndexInfo>emptyList(), Collections.<ForeignKeyInfo>emptyList(), Collections.<ConstraintInfo>emptyList(), null);
    }

    public TableInfo(QualifiedTableName name, boolean view, RecordSchema schema, List<ColumnInfo> columns,
                     PrimaryKeyInfo primaryKey, List<IndexInfo> indexes, String remarks) {
        this(name, view, view ? "VIEW" : "TABLE", schema, columns, primaryKey, indexes, Collections.<ForeignKeyInfo>emptyList(), Collections.<ConstraintInfo>emptyList(), remarks);
    }
    public TableInfo(QualifiedTableName name, boolean view, String type, RecordSchema schema, List<ColumnInfo> columns,
                     PrimaryKeyInfo primaryKey, List<IndexInfo> indexes, List<ForeignKeyInfo> foreignKeys,
                     List<ConstraintInfo> constraints, String remarks) {
        this.name = name;
        this.view = view;
        this.type = type;
        this.schema = schema;
        this.columns = Collections.unmodifiableList(new ArrayList<ColumnInfo>(columns));
        this.primaryKey = primaryKey;
        this.indexes = Collections.unmodifiableList(new ArrayList<IndexInfo>(indexes));
        this.foreignKeys = Collections.unmodifiableList(new ArrayList<ForeignKeyInfo>(foreignKeys));
        this.constraints = Collections.unmodifiableList(new ArrayList<ConstraintInfo>(constraints));
        this.remarks = remarks;
    }

    public QualifiedTableName getName() {
        return name;
    }

    public boolean isView() {
        return view;
    }
    public String getType() { return type; }

    public RecordSchema getSchema() {
        return schema;
    }
    public List<ColumnInfo> getColumns() { return columns; }
    public PrimaryKeyInfo getPrimaryKey() { return primaryKey; }
    public List<IndexInfo> getIndexes() { return indexes; }
    public String getRemarks() { return remarks; }
    public List<ForeignKeyInfo> getForeignKeys() { return foreignKeys; }
    public List<ConstraintInfo> getConstraints() { return constraints; }
}
