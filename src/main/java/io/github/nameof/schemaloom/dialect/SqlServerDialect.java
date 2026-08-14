package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;

final class SqlServerDialect extends AbstractDialect {
    private final java.util.Map<LogicalType, DatabaseTypeMapping> mappings = mappings();
    @Override
    protected String quoteChar() {
        return "\"";
    }

    @Override
    public String type(FieldSchema f) {
        DatabaseTypeMapping mapping = mapping(f.getLogicalType());
        if (!mapping.isSupported()) throw new IllegalArgumentException("SQL Server does not support " + f.getLogicalType());
        switch (f.getLogicalType()) {
            case BOOLEAN:
                return "BIT";
            case INT16:
                return "SMALLINT";
            case INT32:
                return "INT";
            case INT64:
                return "BIGINT";
            case DECIMAL:
                return "DECIMAL(" + Math.min(f.getPrecision() == null ? 38 : f.getPrecision(), 38) + "," + Math.min(f.getScale() == null ? 0 : f.getScale(), 38) + ")";
            case FLOAT32:
                return "REAL";
            case FLOAT64:
                return "FLOAT";
            case DATE:
                return "DATE";
            case TIME:
                return "TIME";
            case TIMESTAMP:
                return "DATETIME2";
            case BINARY:
                return (f.getLength() != null && f.getLength() <= 8000 ? "VARBINARY(" + f.getLength() + ")" : "VARBINARY(MAX)");
            case OFFSET_TIME:
                return "TIME";
            case OFFSET_TIMESTAMP:
                return "DATETIMEOFFSET";
            case STRING:
                Integer n = f.getLength();
                return n != null && n <= 4000 ? "NVARCHAR(" + n + ")" : "NVARCHAR(MAX)";
            default: throw new IllegalArgumentException("unhandled logical type: " + f.getLogicalType());
        }
    }

    @Override
    public DatabaseTypeMapping mapping(LogicalType type) { return mappings.get(type); }

    private java.util.Map<LogicalType, DatabaseTypeMapping> mappings() {
        java.util.EnumMap<LogicalType, DatabaseTypeMapping> mappings = new java.util.EnumMap<LogicalType, DatabaseTypeMapping>(LogicalType.class);
        // 方言矩阵与 DDL 类型保持同一份显式清单，新增逻辑类型必须同步评估。
        mappings.put(LogicalType.BOOLEAN, DatabaseTypeMapping.supported("BIT"));
        mappings.put(LogicalType.INT16, DatabaseTypeMapping.supported("SMALLINT"));
        mappings.put(LogicalType.INT32, DatabaseTypeMapping.supported("INT"));
        mappings.put(LogicalType.INT64, DatabaseTypeMapping.supported("BIGINT"));
        mappings.put(LogicalType.DECIMAL, DatabaseTypeMapping.supported("DECIMAL"));
        mappings.put(LogicalType.FLOAT32, DatabaseTypeMapping.supported("REAL"));
        mappings.put(LogicalType.FLOAT64, DatabaseTypeMapping.supported("FLOAT"));
        mappings.put(LogicalType.STRING, DatabaseTypeMapping.supported("NVARCHAR"));
        mappings.put(LogicalType.DATE, DatabaseTypeMapping.supported("DATE"));
        mappings.put(LogicalType.TIME, DatabaseTypeMapping.supported("TIME"));
        mappings.put(LogicalType.TIMESTAMP, DatabaseTypeMapping.supported("DATETIME2"));
        mappings.put(LogicalType.BINARY, DatabaseTypeMapping.supported("VARBINARY"));
        mappings.put(LogicalType.OFFSET_TIME, DatabaseTypeMapping.supported("TIME"));
        mappings.put(LogicalType.OFFSET_TIMESTAMP, DatabaseTypeMapping.supported("DATETIMEOFFSET"));
        requireCompleteMappings(mappings);
        return java.util.Collections.unmodifiableMap(mappings);
    }
}
