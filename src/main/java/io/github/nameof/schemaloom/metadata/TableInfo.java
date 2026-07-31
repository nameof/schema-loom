package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.api.RecordSchema;
import java.util.*;

public final class TableInfo {
    private final QualifiedTableName name;
    private final boolean view;
    private final RecordSchema schema;
    private final List<ColumnInfo> columns;
    private final PrimaryKeyInfo primaryKey;
    private final List<IndexInfo> indexes;
    private final String remarks;

    public TableInfo(QualifiedTableName name, boolean view, RecordSchema schema) {
        this(name, view, schema, Collections.<ColumnInfo>emptyList(), null, Collections.<IndexInfo>emptyList(), null);
    }

    public TableInfo(QualifiedTableName name, boolean view, RecordSchema schema, List<ColumnInfo> columns,
                     PrimaryKeyInfo primaryKey, List<IndexInfo> indexes, String remarks) {
        this.name = name;
        this.view = view;
        this.schema = schema;
        this.columns = Collections.unmodifiableList(new ArrayList<ColumnInfo>(columns));
        this.primaryKey = primaryKey;
        this.indexes = Collections.unmodifiableList(new ArrayList<IndexInfo>(indexes));
        this.remarks = remarks;
    }

    public QualifiedTableName getName() {
        return name;
    }

    public boolean isView() {
        return view;
    }

    public RecordSchema getSchema() {
        return schema;
    }
    public List<ColumnInfo> getColumns() { return columns; }
    public PrimaryKeyInfo getPrimaryKey() { return primaryKey; }
    public List<IndexInfo> getIndexes() { return indexes; }
    public String getRemarks() { return remarks; }
}
