package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;
import java.sql.*;

public final class JdbcValueCodec {
    private JdbcValueCodec() { }

    public static Object read(ResultSet rs, int index, FieldSchema field) throws SQLException {
        return LogicalTypeCatalog.get(field.getLogicalType()).readJdbc(rs, index);
    }

    public static void write(PreparedStatement ps, int index, FieldSchema field, Object value) throws SQLException {
        LogicalTypeCatalog.get(field.getLogicalType()).writeJdbc(ps, index, value);
    }
}
