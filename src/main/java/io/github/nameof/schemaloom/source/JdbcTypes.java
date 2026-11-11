package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;

import java.sql.Types;

public final class JdbcTypes {
    private JdbcTypes() {
    }

    /**
     * 带类型名的逻辑类型判定，用于处理不在 {@link Types} 里的厂商类型码。
     * <p>
     * Oracle 的 {@code BINARY_FLOAT} / {@code BINARY_DOUBLE} 驱动上报厂商码 100 / 101，
     * SchemaCrawler 又把它归一化成 {@code UNKNOWN}，两条路径都拿不到标准码，只能按类型名识别。
     */
    public static LogicalType logical(int t, String typeName) {
        if ("BINARY_FLOAT".equalsIgnoreCase(typeName)) return LogicalType.FLOAT32;
        if ("BINARY_DOUBLE".equalsIgnoreCase(typeName)) return LogicalType.FLOAT64;
        return logical(t);
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
            case Types.REAL:
                return LogicalType.FLOAT32;
            case Types.FLOAT:
                // JDBC 规范里 FLOAT 对应 double；Oracle 的 FLOAT（二进制精度默认 126 位）
                // 与达梦的 FLOAT（双精度）都上报 Types.FLOAT，按 FLOAT32 处理会丢精度。
                // 实测达梦 FLOAT 存 1.23456789012345，按 FLOAT32 读回只剩 1.2345679。
                // 单精度列在各库都上报 Types.REAL（SQL Server float(24)/real、MySQL 系 FLOAT、
                // DB2 REAL、PostgreSQL float4、Oracle BINARY_FLOAT 上报厂商码 100，不走这里）。
            case Types.DOUBLE:
                return LogicalType.FLOAT64;
            case Types.DATE:
                return LogicalType.DATE;
            case Types.TIME:
                return LogicalType.TIME;
            case Types.TIME_WITH_TIMEZONE:
                return LogicalType.OFFSET_TIME;
            case Types.TIMESTAMP:
                return LogicalType.TIMESTAMP;
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return LogicalType.OFFSET_TIMESTAMP;
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
