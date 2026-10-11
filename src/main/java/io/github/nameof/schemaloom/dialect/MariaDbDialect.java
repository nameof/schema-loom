package io.github.nameof.schemaloom.dialect;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ReUtil;
import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.metadata.ColumnInfo;
import io.github.nameof.schemaloom.metadata.TableInfo;
import io.github.nameof.schemaloom.metadata.IndexInfo;

import java.util.*;

final class MariaDbDialect extends AbstractDialect {
    private final java.util.Map<LogicalType, DatabaseTypeMapping> mappings = mappings();
    @Override
    protected String quoteChar() {
        return "`";
    }

    @Override
    public DatabaseTypeMapping mapping(LogicalType type) { return mappings.get(type); }

    @Override
    protected String defaultValue(ColumnInfo column) {
        String value = column.getDefaultValue();
        if (value != null && ReUtil.isMatch("(?i)NOW\\(\\)|CURRENT_TIMESTAMP\\(\\)", StrUtil.trim(value))) return "CURRENT_TIMESTAMP";
        return super.defaultValue(column);
    }

    @Override
    protected String identityColumn(ColumnInfo column) { return "AUTO_INCREMENT"; }

    @Override
    public String renameTableSql(String table, String newTable) {
        return "ALTER TABLE " + table + " RENAME TO " + newTable;
    }

    /** 仅迁移表注释；字段注释需要完整列定义，避免丢失列属性。 */
    @Override
    public List<String> commentSql(String table, TableInfo source) {
        List<String> sql = new ArrayList<String>();
        if (source.getRemarks() != null)
            sql.add("ALTER TABLE " + table + " COMMENT = '" + StrUtil.replace(source.getRemarks(), "'", "''") + "'");
        // 当前不迁移字段注释。
        return sql;
    }

    @Override
    public ViewDefinitionQuery viewDefinitionQuery(DatabaseConnectionInfo source, QualifiedTableName view) {
        String schema = view.getCatalog() == null ? source.getCatalog() : view.getCatalog();
        if (schema == null) schema = source.getDatabase();
        return new ViewDefinitionQuery("SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", Arrays.<Object>asList(schema, view.getTable()));
    }

    /** 主键已由 CREATE TABLE 创建，MariaDB 保留的 PRIMARY 索引不能再次创建。 */
    @Override
    public List<String> indexSql(String table, List<IndexInfo> indexes) {
        List<IndexInfo> secondary = new ArrayList<IndexInfo>();
        for (IndexInfo index : indexes)
            if (!StrUtil.equalsIgnoreCase("PRIMARY", index.getName())) secondary.add(index);
        return super.indexSql(table, secondary);
    }
    private java.util.Map<LogicalType, DatabaseTypeMapping> mappings() {
        java.util.EnumMap<LogicalType, DatabaseTypeMapping> mappings = new java.util.EnumMap<LogicalType, DatabaseTypeMapping>(LogicalType.class);
        // 这里必须逐项登记；不要用遍历枚举加默认 supported，避免新增类型被错误放行。
        mappings.put(LogicalType.BOOLEAN, DatabaseTypeMapping.supported("BOOLEAN"));
        mappings.put(LogicalType.INT16, DatabaseTypeMapping.supported("SMALLINT"));
        mappings.put(LogicalType.INT32, DatabaseTypeMapping.supported("INT"));
        mappings.put(LogicalType.INT64, DatabaseTypeMapping.supported("BIGINT"));
        mappings.put(LogicalType.DECIMAL, DatabaseTypeMapping.supported(f ->
                "DECIMAL(" + Math.min(f.getPrecision() == null ? 65 : f.getPrecision(), 65) + ","
                        + Math.min(f.getScale() == null ? 0 : f.getScale(), 38) + ")"));
        mappings.put(LogicalType.FLOAT32, DatabaseTypeMapping.supported("FLOAT"));
        mappings.put(LogicalType.FLOAT64, DatabaseTypeMapping.supported("DOUBLE"));
        mappings.put(LogicalType.STRING, DatabaseTypeMapping.supported(f -> {
            Integer length = f.getLength();
            return length != null && length <= 255 ? "VARCHAR(" + length + ")"
                    : length != null && length <= 65535 ? "TEXT" : "LONGTEXT";
        }));
        mappings.put(LogicalType.DATE, DatabaseTypeMapping.supported("DATE"));
        mappings.put(LogicalType.TIME, DatabaseTypeMapping.supported("TIME"));
        mappings.put(LogicalType.TIMESTAMP, DatabaseTypeMapping.supported("DATETIME"));
        mappings.put(LogicalType.BINARY, DatabaseTypeMapping.supported("BLOB"));
        mappings.put(LogicalType.OFFSET_TIME, DatabaseTypeMapping.supported("TIME"));
        mappings.put(LogicalType.OFFSET_TIMESTAMP, DatabaseTypeMapping.supported("DATETIME"));
        requireCompleteMappings(mappings);
        return java.util.Collections.unmodifiableMap(mappings);
    }
}
