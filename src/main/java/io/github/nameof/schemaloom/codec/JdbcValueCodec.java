package io.github.nameof.schemaloom.codec;

import io.github.nameof.schemaloom.api.*;
import java.sql.*;

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
        try {
            LogicalTypeCatalog.get(field.getLogicalType()).writeJdbc(ps, index, value);
        } catch (SQLException e) {
            throw new SQLException("cannot write JDBC value for field '" + field.getName() + "': " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid JDBC value for field '" + field.getName() + "': " + e.getMessage(), e);
        }
    }
}
