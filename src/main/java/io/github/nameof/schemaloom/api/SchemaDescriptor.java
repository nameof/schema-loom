package io.github.nameof.schemaloom.api;

import io.github.nameof.schemaloom.metadata.TableInfo;

/**
 * 保存RecordSchema及可选的数据库表信息。
 *
 * <p>{@code RecordSchema} 必须存在，用来描述记录有哪些字段及通用字段基本信息；
 * {@code TableInfo} 可以没有，它用来保存表名、列注释、索引等数据库特有的额外信息。
 * 只有RecordSchema时元数据以它为准；
 * 只有TableInfo时，RecordSchema会从表信息中自动生成。</p>
 */
public final class SchemaDescriptor {
    private final RecordSchema schema;
    private final TableInfo tableInfo;

    private SchemaDescriptor(RecordSchema schema, TableInfo tableInfo) {
        this.schema = schema;
        this.tableInfo = tableInfo;
    }

    /** 使用RecordSchema创建，不包含数据库表信息。 */
    public static SchemaDescriptor of(RecordSchema schema) {
        if (schema == null) throw new IllegalArgumentException("record schema is required");
        return new SchemaDescriptor(schema, null);
    }

    /**
     * 使用TableInfo创建，RecordSchema会从表信息中自动生成。
     */
    public static SchemaDescriptor of(TableInfo tableInfo) {
        if (tableInfo == null) throw new IllegalArgumentException("table metadata is required");
        return new SchemaDescriptor(tableInfo.toRecordSchema(), tableInfo);
    }

    public RecordSchema getSchema() { return schema; }
    public TableInfo getTableInfo() { return tableInfo; }
}
