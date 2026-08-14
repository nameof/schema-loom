package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;

final class OracleDialect extends AbstractDialect {
    private final java.util.Map<LogicalType, DatabaseTypeMapping> mappings = mappings();
    @Override
    protected String quoteChar() {
        return "\"";
    }

    @Override
    public String type(FieldSchema f) {
        DatabaseTypeMapping mapping = mapping(f.getLogicalType());
        if (!mapping.isSupported()) throw new IllegalArgumentException("Oracle does not support " + f.getLogicalType());
        switch (f.getLogicalType()) {
            case BOOLEAN:
                return "CHAR(1)";
            case INT16:
                return "NUMBER(5)";
            case INT32:
                return "NUMBER(10)";
            case INT64:
                return "NUMBER(19)";
            case DECIMAL:
                return "NUMBER(" + Math.min(f.getPrecision() == null ? 38 : f.getPrecision(), 38) + "," + Math.min(f.getScale() == null ? 0 : f.getScale(), 127) + ")";
            case FLOAT32:
            case FLOAT64:
                return "FLOAT";
            case DATE:
                return "DATE";
            case TIME:
            case TIMESTAMP:
                return "TIMESTAMP";
            case BINARY:
                return "BLOB";
            case OFFSET_TIMESTAMP:
                return "TIMESTAMP WITH TIME ZONE";
            case STRING:
                Integer n = f.getLength();
                return n != null && n <= 4000 ? "VARCHAR2(" + n + ")" : "CLOB";
            default: throw new IllegalArgumentException("unhandled logical type: " + f.getLogicalType());
        }
    }

    @Override
    public DatabaseTypeMapping mapping(LogicalType type) { return mappings.get(type); }

    private java.util.Map<LogicalType, DatabaseTypeMapping> mappings() {
        java.util.EnumMap<LogicalType, DatabaseTypeMapping> mappings = new java.util.EnumMap<LogicalType, DatabaseTypeMapping>(LogicalType.class);
        mappings.put(LogicalType.BOOLEAN, DatabaseTypeMapping.supported("CHAR(1)"));
        mappings.put(LogicalType.INT16, DatabaseTypeMapping.supported("NUMBER(5)"));
        mappings.put(LogicalType.INT32, DatabaseTypeMapping.supported("NUMBER(10)"));
        mappings.put(LogicalType.INT64, DatabaseTypeMapping.supported("NUMBER(19)"));
        mappings.put(LogicalType.DECIMAL, DatabaseTypeMapping.supported("NUMBER"));
        mappings.put(LogicalType.FLOAT32, DatabaseTypeMapping.supported("FLOAT"));
        mappings.put(LogicalType.FLOAT64, DatabaseTypeMapping.supported("FLOAT"));
        mappings.put(LogicalType.STRING, DatabaseTypeMapping.supported("VARCHAR2/CLOB"));
        mappings.put(LogicalType.DATE, DatabaseTypeMapping.supported("DATE"));
        mappings.put(LogicalType.TIME, DatabaseTypeMapping.supported("TIMESTAMP"));
        mappings.put(LogicalType.TIMESTAMP, DatabaseTypeMapping.supported("TIMESTAMP"));
        mappings.put(LogicalType.BINARY, DatabaseTypeMapping.supported("BLOB"));
        mappings.put(LogicalType.OFFSET_TIME, DatabaseTypeMapping.supported("TIMESTAMP WITH TIME ZONE"));
        mappings.put(LogicalType.OFFSET_TIMESTAMP, DatabaseTypeMapping.supported("TIMESTAMP WITH TIME ZONE"));
        requireCompleteMappings(mappings);
        return java.util.Collections.unmodifiableMap(mappings);
    }
}
