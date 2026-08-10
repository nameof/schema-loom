package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;

import java.sql.*;
import java.time.*;
import java.util.*;

public final class JdbcQuerySource implements Source {
    private final ConnectionProvider provider;
    private final String sql;
    private final List<Object> params;
    private final int fetchSize;
    private RecordSchema schema;

    public JdbcQuerySource(DatabaseConnectionInfo info, String sql, List<Object> params, int fetchSize) {
        this(JdbcConnectionFactory.open(info), sql, params, fetchSize);
    }

    public JdbcQuerySource(DatabaseConnectionInfo info, String sql, List<Object> params, int fetchSize, JdbcDriverLoader loader) {
        this(JdbcConnectionFactory.open(info, loader), sql, params, fetchSize);
    }

    public JdbcQuerySource(ConnectionProvider p, String sql, List<Object> params, int fetchSize) {
        if (sql == null || !sql.trim().toLowerCase(Locale.ENGLISH).startsWith("select"))
            throw new IllegalArgumentException("only SELECT is allowed");
        provider = p;
        this.sql = sql;
        this.params = params == null ? Collections.<Object>emptyList() : new ArrayList<Object>(params);
        this.fetchSize = fetchSize;
    }

    public RecordSchema schema() {
        if (schema == null) try {
            PreparedStatement s = provider.getConnection().prepareStatement(sql);
            try {
                bind(s);
                ResultSet r = s.executeQuery();
                try {
                    schema = readSchema(r.getMetaData());
                } finally {
                    r.close();
                }
            } finally {
                s.close();
            }
            return schema;
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot inspect query", e);
        }
        return schema;
    }

    private RecordSchema readSchema(ResultSetMetaData m) throws SQLException {
        List<FieldSchema> fs = new ArrayList<FieldSchema>();
        for (int i = 1; i <= m.getColumnCount(); i++)
            fs.add(new FieldSchema(m.getColumnLabel(i), JdbcTypes.logical(m.getColumnType(i)), m.isNullable(i) != ResultSetMetaData.columnNoNulls, m.getColumnDisplaySize(i), m.getPrecision(i), m.getScale(i)));
        return new RecordSchema(fs);
    }

    private void bind(PreparedStatement s) throws SQLException {
        for (int i = 0; i < params.size(); i++) s.setObject(i + 1, params.get(i));
    }

    public void read(BatchConsumer c) {
        RecordSchema sc = schema();
        try {
            PreparedStatement s = provider.getConnection().prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            try {
                s.setFetchSize(fetchSize);
                bind(s);
                ResultSet r = s.executeQuery();
                try {
                    List<DataRecord> b = new ArrayList<DataRecord>();
                    while (r.next()) {
                        List<Object> v = new ArrayList<Object>();
                        for (FieldSchema f : sc.getFields())
                            v.add(r.getObject(f.getName()));
                        b.add(new DataRecord(sc, v));
                        if (b.size() == fetchSize) {
                            c.accept(new RecordBatch(sc, b));
                            b = new ArrayList<>();
                        }
                    }
                    if (!b.isEmpty()) c.accept(new RecordBatch(sc, b));
                } finally {
                    r.close();
                }
            } finally {
                s.close();
            }
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot read query", e);
        }
    }

    public void close() {
        provider.close();
    }
}
