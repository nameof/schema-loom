package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;

final class OracleDialect extends AbstractDialect {
    protected String quoteChar() {
        return "\"";
    }

    public String type(FieldSchema f) {
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
            default:
                Integer n = f.getLength();
                return n != null && n <= 4000 ? "VARCHAR2(" + n + ")" : "CLOB";
        }
    }
}
