package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;

final class SqlServerDialect extends AbstractDialect {
    protected String quoteChar() {
        return "\"";
    }

    public String type(FieldSchema f) {
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
            default:
                Integer n = f.getLength();
                return n != null && n <= 4000 ? "NVARCHAR(" + n + ")" : "NVARCHAR(MAX)";
        }
    }
}
