package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;

import java.util.*;

final class OracleDialect extends AbstractDialect {
    private final java.util.Map<LogicalType, DatabaseTypeMapping> mappings = mappings();
    @Override
    protected String quoteChar() {
        return "\"";
    }

    @Override
    public DatabaseTypeMapping mapping(LogicalType type) { return mappings.get(type); }

    @Override
    public ViewDefinitionQuery viewDefinitionQuery(DatabaseConnectionInfo source, QualifiedTableName view) {
        String owner = view.getSchema() == null ? source.getSchema() : view.getSchema();
        if (owner == null) throw new IllegalArgumentException("Oracle view migration requires a source schema");
        return new ViewDefinitionQuery("SELECT TEXT FROM ALL_VIEWS WHERE OWNER = ? AND VIEW_NAME = ?",
                Arrays.<Object>asList(owner.toUpperCase(Locale.ENGLISH), view.getTable().toUpperCase(Locale.ENGLISH)));
    }

    private java.util.Map<LogicalType, DatabaseTypeMapping> mappings() {
        java.util.EnumMap<LogicalType, DatabaseTypeMapping> mappings = new java.util.EnumMap<LogicalType, DatabaseTypeMapping>(LogicalType.class);
        mappings.put(LogicalType.BOOLEAN, DatabaseTypeMapping.supported("CHAR(1)"));
        mappings.put(LogicalType.INT16, DatabaseTypeMapping.supported("NUMBER(5)"));
        mappings.put(LogicalType.INT32, DatabaseTypeMapping.supported("NUMBER(10)"));
        mappings.put(LogicalType.INT64, DatabaseTypeMapping.supported("NUMBER(19)"));
        mappings.put(LogicalType.DECIMAL, DatabaseTypeMapping.supported(f ->
                "NUMBER(" + Math.min(f.getPrecision() == null ? 38 : f.getPrecision(), 38) + ","
                        + Math.min(f.getScale() == null ? 0 : f.getScale(), 127) + ")"));
        mappings.put(LogicalType.FLOAT32, DatabaseTypeMapping.supported("FLOAT"));
        mappings.put(LogicalType.FLOAT64, DatabaseTypeMapping.supported("FLOAT"));
        mappings.put(LogicalType.STRING, DatabaseTypeMapping.supported(f -> {
            Integer length = f.getLength();
            return length != null && length <= 4000 ? "VARCHAR2(" + length + ")" : "CLOB";
        }));
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
