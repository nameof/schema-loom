package io.github.nameof.schemaloom.target;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.dialect.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.source.JdbcTypes;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;

import java.sql.*;
import java.util.*;

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
        schema = s;
        Connection c = provider.getConnection();
        try {
            boolean exists = false;
            ResultSet r = c.getMetaData().getTables(table.getCatalog(), table.getSchema(), table.getTable(), null);
            try {
                while (r.next()) {
                    String name = r.getString("TABLE_NAME");
                    if (name == null || table.getTable().equals(name)) {
                        exists = true;
                        break;
                    }
                }
            } finally {
                r.close();
            }
            String q = dialect.quote(table);
            if (mode == TargetMode.REPLACE && exists) {
                c.createStatement().executeUpdate(dialect.dropTable(q));
                exists = false;
            }
            if (!exists) {
                c.createStatement().executeUpdate(dialect.createTable(q, s));
            } else {
                validateAppend(c.getMetaData(), s, table);
            }
            prepared = true;
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot prepare JDBC target", e);
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
                        ps.setObject(i + 1, r.get(i));
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

    public void close() {
        provider.close();
    }

    /**
     * 校验已存在的目标表是否可以安全接收源 Schema。
     * APPEND 不修改目标表，因此所有不兼容情况都必须在这里提前失败。
     */
    private void validateAppend(DatabaseMetaData metadata, RecordSchema source, QualifiedTableName tableName) throws SQLException {
        Map<String, ExistingColumn> target = new LinkedHashMap<String, ExistingColumn>();
        ResultSet r = metadata.getColumns(tableName.getCatalog(), tableName.getSchema(), tableName.getTable(), "%");
        try {
            while (r.next()) {
                String name = r.getString("COLUMN_NAME");
                // 保留未匹配的目标字段，后面统一检查它们是否为必填额外字段。
                target.put(name.toLowerCase(Locale.ENGLISH), new ExistingColumn(
                        name, JdbcTypes.logical(r.getInt("DATA_TYPE")), r.getInt("COLUMN_SIZE"),
                        r.getInt("DECIMAL_DIGITS"), "YES".equalsIgnoreCase(r.getString("IS_NULLABLE")),
                        r.getString("COLUMN_DEF"), "YES".equalsIgnoreCase(r.getString("IS_AUTOINCREMENT")),
                        "YES".equalsIgnoreCase(r.getString("IS_GENERATEDCOLUMN"))));
            }
        } finally {
            r.close();
        }
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
