package io.github.nameof.schemaloom.target;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.codec.JdbcValueCodec;
import io.github.nameof.schemaloom.dialect.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.metadata.*;

import java.sql.*;
import java.util.*;

/**
 * JDBC 普通表 Target。
 *
 * <p>目标表不存在时，{@link #prepare} 会依据 Source
 * Schema 自动生成普通表；目标已存在时，{@code APPEND} 只在结构兼容时追加，
 * {@code REPLACE} 会删除并重建目标表。目标表名始终由调用方指定，不能从
 * </p>
 *
 * <p>VIEW：只能作为数据只读来源，不能作为 Target 的写入对象。若Target对象已经是 VIEW，准备阶段会失败；
 * 需要在目标库创建 VIEW 定义时，应使用独立的 {@code JdbcViewMigrationTask}，而不是把 VIEW 当普通表写入数据。</p>
 */
public final class JdbcTableTarget implements Target {
    private final ConnectionProvider provider;
    private final QualifiedTableName table;
    private final DatabaseDialect dialect;
    private RecordSchema schema;
    private boolean prepared;

    JdbcTableTarget(ConnectionProvider p, QualifiedTableName table, DatabaseType type) {
        provider = p;
        this.table = table;
        this.dialect = new DialectRegistry().get(type);
    }

    public JdbcTableTarget(DatabaseConnectionInfo info, String table) {
        this(info, table, null);
    }

    public JdbcTableTarget(DatabaseConnectionInfo info, String table, JdbcDriverLoader loader) {
        this(JdbcConnectionFactory.open(info, loader == null ? new JdbcDriverLoader() : loader), info.table(table), info.getDatabaseType());
    }

    public void prepare(RecordSchema s, TargetMode mode) {
        prepare(new SchemaDescriptor(s), mode);
    }

    @Override
    public void prepare(SchemaDescriptor descriptor, TargetMode mode) {
        if (descriptor == null) throw new IllegalArgumentException("schema descriptor is required");
        schema = descriptor.getSchema();
        TableInfo tableMetadata = descriptor.getTableInfo() == null
                ? new TableInfo(table, false, schema) : descriptor.getTableInfo();
        validateCapabilities(schema);
        Connection c = provider.getConnection();
        try {
            DatabaseMetadataService metadata = new DatabaseMetadataService();
            List<TableInfo> tables = metadata.listTables(provider, new MetadataQuery(table.getCatalog(), table.getSchema(), table.getTable()));
            TableInfo existingTable = tables.isEmpty() ? null : tables.get(0);
            boolean exists = existingTable != null;
            if (exists && existingTable.isView())
                throw new SchemaLoomException("JDBC table target cannot write to a view: " + table.getTable());
            String q = dialect.quote(table);
            if (mode == TargetMode.REPLACE && exists) {
                c.createStatement().executeUpdate(dialect.dropTable(q));
                exists = false;
            }
            if (!exists) {
                c.createStatement().executeUpdate(dialect.createTableSql(q, tableMetadata));
            } else {
                validateAppend(existingTable, schema);
            }
            prepared = true;
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot prepare JDBC target", e);
        }
    }

    private void validateCapabilities(RecordSchema source) {
        for (FieldSchema field : source.getFields()) {
            DatabaseTypeMapping mapping = dialect.mapping(field.getLogicalType());
            if (mapping == null || !mapping.isSupported())
                throw new SchemaLoomException("target does not support logical type " + field.getLogicalType()
                        + " for field '" + field.getName() + "'");
        }
    }

    public BatchWriteResult write(RecordBatch b) {
        if (!prepared)
            throw new SchemaLoomException("target is not prepared");
        Connection c = provider.getConnection();
        boolean old;
        try {
            String sql = dialect.insert(dialect.quote(table), schema);
            old = c.getAutoCommit();
            c.setAutoCommit(false);
            PreparedStatement ps = c.prepareStatement(sql);
            try {
                for (DataRecord r : b.getRecords()) {
                    for (int i = 0; i < schema.getFields().size(); i++)
                        setValue(ps, i + 1, schema.getFields().get(i), r.get(i));
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
                return new BatchWriteResult(b.size(), 0);
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw new SchemaLoomException("JDBC batch failed: " + e.getMessage(), e);
            } finally {
                ps.close();
                c.setAutoCommit(old);
            }
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot write JDBC target: " + e.getMessage(), e);
        }
    }

    private void setValue(PreparedStatement ps, int index, FieldSchema field, Object value) throws SQLException {
        JdbcValueCodec.write(ps, index, field, value);
    }

    public void close() {
        provider.close();
    }

    /**
     * 校验已存在的目标表是否可以安全接收源 Schema。
     * APPEND 不修改目标表，因此所有不兼容情况都必须在这里提前失败。
     */
    private void validateAppend(TableInfo tableInfo, RecordSchema source) {
        Map<String, ExistingColumn> target = new LinkedHashMap<String, ExistingColumn>();
        for (ColumnInfo column : tableInfo.getColumns()) target.put(column.getName().toLowerCase(Locale.ENGLISH), new ExistingColumn(
                column.getName(), column.getLogicalType(), column.getLength() == null ? 0 : column.getLength(),
                column.getScale() == null ? 0 : column.getScale(), column.isNullable(), column.getDefaultValue(),
                column.isAutoIncremented(), column.isGenerated()));
        for (FieldSchema field : source.getFields()) {
            // 移除已匹配字段后，Map 中只剩源 Schema 未提供的目标字段。
            ExistingColumn existing = target.remove(field.getName().toLowerCase(Locale.ENGLISH));
            if (existing == null) throw new SchemaLoomException("target column missing: " + field.getName());
            if (field.isNullable() && !existing.nullable)
                throw new SchemaLoomException("nullable source cannot append to NOT NULL column: " + field.getName());
            if (!safe(field, existing))
                throw new SchemaLoomException("incompatible target column: " + field.getName());
        }
        for (ExistingColumn extra : target.values()) {
            // 额外的必填字段不在源 Schema 中，会导致 INSERT 失败。
            if (!extra.nullable && extra.defaultValue == null && !extra.autoGenerated && !extra.generated)
                throw new SchemaLoomException("target has required extra column: " + extra.name);
        }
    }

    /** 判断源字段写入现有目标字段时是否不会发生有损转换。 */
    private boolean safe(FieldSchema source, ExistingColumn target) {
        LogicalType from = source.getLogicalType(), to = target.type;
        if (from == to) {
            // 逻辑类型相同时，字符串容量和 DECIMAL 精度仍需单独校验。
            if (from == LogicalType.STRING && source.getLength() != null && target.size > 0 && source.getLength() > target.size) return false;
            if (from == LogicalType.DECIMAL && source.getPrecision() != null && target.size > 0 && source.getPrecision() > target.size) return false;
            return from != LogicalType.DECIMAL || source.getScale() == null || target.scale <= 0 || source.getScale() <= target.scale;
        }
        // 数值拓宽和 DATE -> TIMESTAMP 可以保留源值含义。
        if (from == LogicalType.INT16) return to == LogicalType.INT32 || to == LogicalType.INT64 || to == LogicalType.DECIMAL || to == LogicalType.FLOAT32 || to == LogicalType.FLOAT64;
        if (from == LogicalType.INT32) return to == LogicalType.INT64 || to == LogicalType.DECIMAL || to == LogicalType.FLOAT64;
        if (from == LogicalType.INT64) return to == LogicalType.DECIMAL || to == LogicalType.FLOAT64;
        if (from == LogicalType.FLOAT32) return to == LogicalType.FLOAT64 || to == LogicalType.DECIMAL;
        if (from == LogicalType.DATE) return to == LogicalType.TIMESTAMP;
        return false;
    }

    /** APPEND 校验所需的最小目标字段元数据。 */
    private static final class ExistingColumn {
        final String name;
        final LogicalType type;
        final int size, scale;
        final boolean nullable, autoGenerated, generated;
        final String defaultValue;

        ExistingColumn(String name, LogicalType type, int size, int scale, boolean nullable,
                       String defaultValue, boolean autoGenerated, boolean generated) {
            this.name = name; this.type = type; this.size = size; this.scale = scale;
            this.nullable = nullable; this.defaultValue = defaultValue;
            this.autoGenerated = autoGenerated; this.generated = generated;
        }
    }
}
