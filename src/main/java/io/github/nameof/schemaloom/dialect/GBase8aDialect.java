package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.metadata.ColumnInfo;
import io.github.nameof.schemaloom.metadata.TableInfo;

import java.util.*;

// GBase 8a 是 OLAP 分析型数据库，SQL 方言高度兼容 MySQL，但存在以下差异：
// - DECIMAL 精度上限为 38（MySQL 为 65）
// - 不支持 LONGTEXT 类型
// - 无原生 BOOLEAN 关键字（使用 TINYINT 替代）
final class GBase8aDialect extends AbstractDialect {
    private final Map<LogicalType, DatabaseTypeMapping> mappings = mappings();

    @Override
    protected String quoteChar() {
        return "`";
    }

    @Override
    public DatabaseTypeMapping mapping(LogicalType type) { return mappings.get(type); }

    @Override
    protected String defaultValue(ColumnInfo column) {
        String value = column.getDefaultValue();
        // GBase 8a 与 MySQL 一样，NOW()/CURRENT_TIMESTAMP() 需规范化为 CURRENT_TIMESTAMP
        if (value != null && value.trim().matches("(?i)NOW\\(\\)|CURRENT_TIMESTAMP\\(\\)")) return "CURRENT_TIMESTAMP";
        return super.defaultValue(column);
    }

    @Override
    protected String identityColumn(ColumnInfo column) { return "AUTO_INCREMENT"; }

    @Override
    public String renameTableSql(String table, String newTable) {
        return "ALTER TABLE " + table + " RENAME TO " + newTable;
    }

    /** 与 MySQL 一致，仅迁移表注释；字段注释暂不迁移。 */
    @Override
    public List<String> commentSql(String table, TableInfo source) {
        List<String> sql = new ArrayList<String>();
        if (source.getRemarks() != null)
            sql.add("ALTER TABLE " + table + " COMMENT = '" + source.getRemarks().replace("'", "''") + "'");
        return sql;
    }

    @Override
    public ViewDefinitionQuery viewDefinitionQuery(DatabaseConnectionInfo source, QualifiedTableName view) {
        String schema = view.getCatalog() == null ? source.getCatalog() : view.getCatalog();
        if (schema == null) schema = source.getDatabase();
        return new ViewDefinitionQuery("SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", Arrays.<Object>asList(schema, view.getTable()));
    }

    private Map<LogicalType, DatabaseTypeMapping> mappings() {
        EnumMap<LogicalType, DatabaseTypeMapping> mappings = new EnumMap<LogicalType, DatabaseTypeMapping>(LogicalType.class);
        // GBase 8a 无原生 BOOLEAN，使用 TINYINT 替代
        mappings.put(LogicalType.BOOLEAN, DatabaseTypeMapping.supported("TINYINT"));
        mappings.put(LogicalType.INT16, DatabaseTypeMapping.supported("SMALLINT"));
        mappings.put(LogicalType.INT32, DatabaseTypeMapping.supported("INT"));
        mappings.put(LogicalType.INT64, DatabaseTypeMapping.supported("BIGINT"));
        // GBase 8a DECIMAL 精度上限为 38（MySQL 为 65）
        mappings.put(LogicalType.DECIMAL, DatabaseTypeMapping.supported(f ->
                "DECIMAL(" + Math.min(f.getPrecision() == null ? 38 : f.getPrecision(), 38) + ","
                        + Math.min(f.getScale() == null ? 0 : f.getScale(), 30) + ")"));
        mappings.put(LogicalType.FLOAT32, DatabaseTypeMapping.supported("FLOAT"));
        mappings.put(LogicalType.FLOAT64, DatabaseTypeMapping.supported("DOUBLE"));
        mappings.put(LogicalType.STRING, DatabaseTypeMapping.supported(f -> {
            Integer length = f.getLength();
            // GBase 8a 不支持 LONGTEXT，超过 VARCHAR 范围统一使用 TEXT
            return length != null && length <= 255 ? "VARCHAR(" + length + ")" : "TEXT";
        }));
        mappings.put(LogicalType.DATE, DatabaseTypeMapping.supported("DATE"));
        mappings.put(LogicalType.TIME, DatabaseTypeMapping.supported("TIME"));
        mappings.put(LogicalType.TIMESTAMP, DatabaseTypeMapping.supported("DATETIME"));
        mappings.put(LogicalType.BINARY, DatabaseTypeMapping.supported("BLOB"));
        mappings.put(LogicalType.OFFSET_TIME, DatabaseTypeMapping.supported("TIME"));
        mappings.put(LogicalType.OFFSET_TIMESTAMP, DatabaseTypeMapping.supported("DATETIME"));
        requireCompleteMappings(mappings);
        return Collections.unmodifiableMap(mappings);
    }
}
