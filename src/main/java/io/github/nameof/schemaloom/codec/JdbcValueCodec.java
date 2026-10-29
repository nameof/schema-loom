package io.github.nameof.schemaloom.codec;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.dialect.DatabaseDialect;
import io.github.nameof.schemaloom.dialect.DatabaseTypeMapping;

import java.sql.*;
import java.time.OffsetDateTime;
import java.time.OffsetTime;

public final class JdbcValueCodec {
    private JdbcValueCodec() { }

    public static Object read(ResultSet rs, int index, FieldSchema field) throws SQLException {
        try {
            return LogicalTypeCatalog.get(field.getLogicalType()).readJdbc(rs, index);
        } catch (SQLException e) {
            throw new SQLException("cannot read JDBC value for field '" + field.getName() + "': " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid JDBC value for field '" + field.getName() + "': " + e.getMessage(), e);
        }
    }

    public static void write(PreparedStatement ps, int index, FieldSchema field, Object value) throws SQLException {
        write(ps, index, field, value, null);
    }

    /**
     * dialect 非空时，若该逻辑类型在目标方言中被降级（例如带时区的时间降级为无时区的时间），
     * 绑定会跟随降级结果。
     * 否则会出现「DDL 已经是 TIME、绑定却仍用 TIME_WITH_TIMEZONE」的错配，
     * 目标驱动直接报「无法识别的 JDBC 类型」（DB2 实测 ERRORCODE=-4228）。
     */
    public static void write(PreparedStatement ps, int index, FieldSchema field, Object value, DatabaseDialect dialect)
            throws SQLException {
        LogicalType target = field.getLogicalType();
        Object bound = value;
        if (dialect != null) {
            DatabaseTypeMapping mapping = dialect.mapping(target);
            LogicalType degraded = mapping == null ? null : mapping.degradesTo();
            if (degraded != null) {
                bound = degradeValue(target, degraded, value);
                target = degraded;
            }
        }
        try {
            LogicalTypeCatalog.get(target).writeJdbc(ps, index, bound);
        } catch (SQLException e) {
            throw new SQLException("cannot write JDBC value for field '" + field.getName() + "': " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid JDBC value for field '" + field.getName() + "': " + e.getMessage(), e);
        }
    }

    /** 按降级目标丢掉目标库无法表示的成分（时区偏移），与 DDL 的降级保持一致。 */
    private static Object degradeValue(LogicalType from, LogicalType to, Object value) {
        if (value == null) return null;
        if (from == LogicalType.OFFSET_TIME && to == LogicalType.TIME && value instanceof OffsetTime)
            return ((OffsetTime) value).toLocalTime();
        if (from == LogicalType.OFFSET_TIMESTAMP && to == LogicalType.TIMESTAMP && value instanceof OffsetDateTime)
            return ((OffsetDateTime) value).toLocalDateTime();
        return value;
    }
}
