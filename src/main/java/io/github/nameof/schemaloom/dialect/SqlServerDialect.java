package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.metadata.ColumnInfo;

import java.util.*;

final class SqlServerDialect extends AbstractDialect {
    private final java.util.Map<LogicalType, DatabaseTypeMapping> mappings = mappings();
    @Override
    protected String quoteChar() {
        return "\"";
    }

    @Override
    public DatabaseTypeMapping mapping(LogicalType type) { return mappings.get(type); }

    @Override
    protected String defaultValue(ColumnInfo column) {
        String value = column.getDefaultValue();
        if (value != null && value.trim().matches("(?i)GETDATE\\(\\)|GETUTCDATE\\(\\)|SYSDATETIME\\(\\)")) return "CURRENT_TIMESTAMP";
        if (value != null && value.trim().equalsIgnoreCase("TRUE")) return "1";
        if (value != null && value.trim().equalsIgnoreCase("FALSE")) return "0";
        return super.defaultValue(column);
    }

    @Override
    public ViewDefinitionQuery viewDefinitionQuery(DatabaseConnectionInfo source, QualifiedTableName view) {
        String name = view.getSchema() == null ? view.getTable() : view.getSchema() + "." + view.getTable();
        return new ViewDefinitionQuery("SELECT OBJECT_DEFINITION(OBJECT_ID(?))", Collections.<Object>singletonList(name));
    }

    private java.util.Map<LogicalType, DatabaseTypeMapping> mappings() {
        java.util.EnumMap<LogicalType, DatabaseTypeMapping> mappings = new java.util.EnumMap<LogicalType, DatabaseTypeMapping>(LogicalType.class);
        // 方言矩阵与 DDL 类型保持同一份显式清单，新增逻辑类型必须同步评估。
        mappings.put(LogicalType.BOOLEAN, DatabaseTypeMapping.supported("BIT"));
        mappings.put(LogicalType.INT16, DatabaseTypeMapping.supported("SMALLINT"));
        mappings.put(LogicalType.INT32, DatabaseTypeMapping.supported("INT"));
        mappings.put(LogicalType.INT64, DatabaseTypeMapping.supported("BIGINT"));
        mappings.put(LogicalType.DECIMAL, DatabaseTypeMapping.supported(f ->
                "DECIMAL(" + Math.min(f.getPrecision() == null ? 38 : f.getPrecision(), 38) + ","
                        + Math.min(f.getScale() == null ? 0 : f.getScale(), 38) + ")"));
        mappings.put(LogicalType.FLOAT32, DatabaseTypeMapping.supported("REAL"));
        mappings.put(LogicalType.FLOAT64, DatabaseTypeMapping.supported("FLOAT"));
        mappings.put(LogicalType.STRING, DatabaseTypeMapping.supported(f -> {
            Integer length = f.getLength();
            return length != null && length <= 4000 ? "NVARCHAR(" + length + ")" : "NVARCHAR(MAX)";
        }));
        mappings.put(LogicalType.DATE, DatabaseTypeMapping.supported("DATE"));
        mappings.put(LogicalType.TIME, DatabaseTypeMapping.supported("TIME"));
        mappings.put(LogicalType.TIMESTAMP, DatabaseTypeMapping.supported("DATETIME2"));
        mappings.put(LogicalType.BINARY, DatabaseTypeMapping.supported(f ->
                f.getLength() != null && f.getLength() <= 8000
                        ? "VARBINARY(" + f.getLength() + ")" : "VARBINARY(MAX)"));
        mappings.put(LogicalType.OFFSET_TIME, DatabaseTypeMapping.supported("TIME"));
        mappings.put(LogicalType.OFFSET_TIMESTAMP, DatabaseTypeMapping.supported("DATETIMEOFFSET"));
        requireCompleteMappings(mappings);
        return java.util.Collections.unmodifiableMap(mappings);
    }
}
