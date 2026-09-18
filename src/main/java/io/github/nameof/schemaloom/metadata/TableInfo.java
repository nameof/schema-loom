package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.api.FieldSchema;
import io.github.nameof.schemaloom.api.RecordSchema;

import java.util.*;

/** 数据库表的完整元数据；字段定义唯一来源是 columns。 */
public final class TableInfo {
    private final QualifiedTableName name;
    private final boolean view;
    private final String type;
    private final List<ColumnInfo> columns;
    private final PrimaryKeyInfo primaryKey;
    private final List<IndexInfo> indexes;
    private final String remarks;
    private final List<ForeignKeyInfo> foreignKeys;
    private final List<ConstraintInfo> constraints;
    private volatile RecordSchema schema;

    /** 从通用 Schema 创建无额外数据库属性的表定义。 */
    public TableInfo(QualifiedTableName name, boolean view, RecordSchema schema) {
        this(name, view, fields(schema), primaryKey(schema), Collections.<IndexInfo>emptyList(), null);
    }

    private static List<ColumnInfo> fields(RecordSchema schema) {
        if (schema == null) throw new IllegalArgumentException("记录 Schema 不能为空");
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        for (FieldSchema field : schema.getFields())
            columns.add(new ColumnInfo(field, null, null, columns.size() + 1, null, null, false, false));
        return columns;
    }

    private static PrimaryKeyInfo primaryKey(RecordSchema schema) {
        return schema.getPrimaryKeyFields().isEmpty() ? null : new PrimaryKeyInfo(null, schema.getPrimaryKeyFields());
    }

    public TableInfo(QualifiedTableName name, boolean view, List<ColumnInfo> columns,
                     PrimaryKeyInfo primaryKey, List<IndexInfo> indexes, String remarks) {
        this(name, view, view ? "VIEW" : "TABLE", columns, primaryKey, indexes,
                Collections.<ForeignKeyInfo>emptyList(), Collections.<ConstraintInfo>emptyList(), remarks);
    }

    public TableInfo(QualifiedTableName name, boolean view, String type, List<ColumnInfo> columns,
                     PrimaryKeyInfo primaryKey, List<IndexInfo> indexes, List<ForeignKeyInfo> foreignKeys,
                     List<ConstraintInfo> constraints, String remarks) {
        if (columns == null || columns.isEmpty()) throw new IllegalArgumentException("表必须包含列元数据");
        this.name = name;
        this.view = view;
        this.type = type;
        this.columns = Collections.unmodifiableList(new ArrayList<ColumnInfo>(columns));
        this.primaryKey = primaryKey;
        this.indexes = Collections.unmodifiableList(new ArrayList<IndexInfo>(indexes));
        this.foreignKeys = Collections.unmodifiableList(new ArrayList<ForeignKeyInfo>(foreignKeys));
        this.constraints = Collections.unmodifiableList(new ArrayList<ConstraintInfo>(constraints));
        this.remarks = remarks;
    }

    /** 从数据库列投影出数据读写所需的通用 Schema；结果在首次调用后缓存。 */
    public RecordSchema toRecordSchema() {
        if (schema == null) {
            List<FieldSchema> fields = new ArrayList<FieldSchema>();
            for (ColumnInfo column : columns)
                fields.add(column.getFieldSchema());
            List<String> keys = primaryKey == null ? Collections.<String>emptyList() : primaryKey.getColumns();
            schema = new RecordSchema(fields, keys);
        }
        return schema;
    }

    public QualifiedTableName getName() { return name; }
    public boolean isView() { return view; }
    public String getType() { return type; }
    public List<ColumnInfo> getColumns() { return columns; }
    public PrimaryKeyInfo getPrimaryKey() { return primaryKey; }
    public List<IndexInfo> getIndexes() { return indexes; }
    public String getRemarks() { return remarks; }
    public List<ForeignKeyInfo> getForeignKeys() { return foreignKeys; }
    public List<ConstraintInfo> getConstraints() { return constraints; }
}
