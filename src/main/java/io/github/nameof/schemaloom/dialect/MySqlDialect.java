package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;

final class MySqlDialect extends AbstractDialect {
    protected String quoteChar() {
        return "`";
    }

    public String type(FieldSchema f) {
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
            case BINARY:
                return "BLOB";
            default:
                Integer n = f.getLength();
                return n != null && n <= 255 ? "VARCHAR(" + n + ")" : n != null && n <= 65535 ? "TEXT" : "LONGTEXT";
        }
    }
}
