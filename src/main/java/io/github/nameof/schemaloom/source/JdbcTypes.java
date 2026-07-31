package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;

import java.sql.Types;

public final class JdbcTypes {
    private JdbcTypes() {
    }

    public static LogicalType logical(int t) {
        switch (t) {
            case Types.BOOLEAN:
            case Types.BIT:
                return LogicalType.BOOLEAN;
            case Types.TINYINT:
            case Types.SMALLINT:
                return LogicalType.INT16;
            case Types.INTEGER:
                return LogicalType.INT32;
            case Types.BIGINT:
                return LogicalType.INT64;
            case Types.DECIMAL:
            case Types.NUMERIC:
                return LogicalType.DECIMAL;
            case Types.FLOAT:
            case Types.REAL:
                return LogicalType.FLOAT32;
            case Types.DOUBLE:
                return LogicalType.FLOAT64;
            case Types.DATE:
                return LogicalType.DATE;
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return LogicalType.TIME;
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return LogicalType.TIMESTAMP;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return LogicalType.BINARY;
            default:
                return LogicalType.STRING;
        }
    }
}
