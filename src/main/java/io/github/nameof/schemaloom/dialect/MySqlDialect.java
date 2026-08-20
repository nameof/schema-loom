package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.metadata.ColumnInfo;

import java.util.*;

final class MySqlDialect extends AbstractDialect {
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
        if (value != null && value.trim().matches("(?i)NOW\\(\\)|CURRENT_TIMESTAMP\\(\\)")) return "CURRENT_TIMESTAMP";
        return super.defaultValue(column);
    }

    @Override
    public ViewDefinitionQuery viewDefinitionQuery(DatabaseConnectionInfo source, QualifiedTableName view) {
        String schema = view.getCatalog() == null ? source.getCatalog() : view.getCatalog();
        if (schema == null) schema = source.getDatabase();
        return new ViewDefinitionQuery("SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", Arrays.<Object>asList(schema, view.getTable()));
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
                        + Math.min(f.getScale() == null ? 0 : f.getScale(), 30) + ")"));
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
