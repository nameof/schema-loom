package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;

final class MySqlDialect extends AbstractDialect {
    private final java.util.Map<LogicalType, DatabaseTypeMapping> mappings = mappings();
    @Override
    protected String quoteChar() {
        return "`";
    }

    @Override
    public String type(FieldSchema f) {
        DatabaseTypeMapping mapping = mapping(f.getLogicalType());
        if (!mapping.isSupported())
            throw new IllegalArgumentException("MySQL does not support " + f.getLogicalType());
        switch (f.getLogicalType()) {
            case BOOLEAN:
                return "BOOLEAN";
            case INT16:
                return "SMALLINT";
            case INT32:
                return "INT";
            case INT64:
                return "BIGINT";
            case DECIMAL:
                return "DECIMAL(" + Math.min(f.getPrecision() == null ? 65 : f.getPrecision(), 65) + "," + Math.min(f.getScale() == null ? 0 : f.getScale(), 30) + ")";
            case FLOAT32:
                return "FLOAT";
            case FLOAT64:
                return "DOUBLE";
            case DATE:
                return "DATE";
            case TIME:
                return "TIME";
            case TIMESTAMP:
                return "DATETIME";
            case OFFSET_TIME:
                return "TIME";
            case OFFSET_TIMESTAMP:
                return "DATETIME";
            case BINARY:
                return "BLOB";
            case STRING:
                Integer n = f.getLength();
                return n != null && n <= 255 ? "VARCHAR(" + n + ")" : n != null && n <= 65535 ? "TEXT" : "LONGTEXT";
            default: throw new IllegalArgumentException("unhandled logical type: " + f.getLogicalType());
        }
    }

    @Override
    public DatabaseTypeMapping mapping(LogicalType type) { return mappings.get(type); }

    private java.util.Map<LogicalType, DatabaseTypeMapping> mappings() {
        java.util.EnumMap<LogicalType, DatabaseTypeMapping> mappings = new java.util.EnumMap<LogicalType, DatabaseTypeMapping>(LogicalType.class);
        // 这里必须逐项登记；不要用遍历枚举加默认 supported，避免新增类型被错误放行。
        mappings.put(LogicalType.BOOLEAN, DatabaseTypeMapping.supported("BOOLEAN"));
        mappings.put(LogicalType.INT16, DatabaseTypeMapping.supported("SMALLINT"));
        mappings.put(LogicalType.INT32, DatabaseTypeMapping.supported("INT"));
        mappings.put(LogicalType.INT64, DatabaseTypeMapping.supported("BIGINT"));
        mappings.put(LogicalType.DECIMAL, DatabaseTypeMapping.supported("DECIMAL"));
        mappings.put(LogicalType.FLOAT32, DatabaseTypeMapping.supported("FLOAT"));
        mappings.put(LogicalType.FLOAT64, DatabaseTypeMapping.supported("DOUBLE"));
        mappings.put(LogicalType.STRING, DatabaseTypeMapping.supported("VARCHAR/TEXT"));
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
