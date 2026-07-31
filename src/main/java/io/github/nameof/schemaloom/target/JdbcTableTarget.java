package io.github.nameof.schemaloom.target;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.dialect.*;
import io.github.nameof.schemaloom.driver.*;

import java.sql.*;

public final class JdbcTableTarget implements Target {
    private final ConnectionProvider provider;
    private final String table;
    private final DatabaseDialect dialect;
    private RecordSchema schema;
    private boolean prepared;

    public JdbcTableTarget(ConnectionProvider p, String table, DatabaseType type) {
        provider = p;
        this.table = table;
        this.dialect = new DialectRegistry().get(type);
    }

    public void prepare(RecordSchema s, TargetMode mode) {
        schema = s;
        Connection c = provider.getConnection();
        try {
            boolean exists = false;
            ResultSet r = c.getMetaData().getTables(null, null, table, null);
            try {
                exists = r.next();
            } finally {
                r.close();
            }
            String q = dialect.quote(table);
            if (mode == TargetMode.REPLACE && exists) {
                c.createStatement().executeUpdate(dialect.dropTable(q));
                exists = false;
            }
            if (!exists) c.createStatement().executeUpdate(dialect.createTable(q, s));
            prepared = true;
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot prepare JDBC target", e);
        }
    }

    public BatchWriteResult write(RecordBatch b) {
        if (!prepared) throw new SchemaLoomException("target is not prepared");
        Connection c = provider.getConnection();
        String sql = dialect.insert(dialect.quote(table), schema);
        boolean old;
        try {
            old = c.getAutoCommit();
            c.setAutoCommit(false);
            PreparedStatement ps = c.prepareStatement(sql);
            try {
                for (DataRecord r : b.getRecords()) {
                    for (int i = 0; i < schema.getFields().size(); i++) ps.setObject(i + 1, r.get(i));
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
                return new BatchWriteResult(b.size(), 0);
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw new SchemaLoomException("JDBC batch failed", e);
            } finally {
                ps.close();
                c.setAutoCommit(old);
            }
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot write JDBC target", e);
        }
    }

    public void close() {
        provider.close();
    }
}
