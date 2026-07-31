package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.metadata.*;

import java.sql.*;
import java.util.*;

public final class JdbcTableSource implements Source {
    private final JdbcQuerySource delegate;
    private final RecordSchema tableSchema;

    public JdbcTableSource(ConnectionProvider p, QualifiedTableName t) {
        this(p, t, 1000);
    }

    public JdbcTableSource(ConnectionProvider p, QualifiedTableName t, int fetchSize) {
        try {
            TableInfo tableInfo = new DatabaseMetadataService().getTable(p, t);
            tableSchema = tableInfo.getSchema();
            String qmark = p.getConnection().getMetaData().getIdentifierQuoteString();
            String q = (qmark == null || qmark.trim().isEmpty()) ? "" : qmark.trim();
            StringBuilder sql = new StringBuilder("SELECT * FROM ");
            if (t.getCatalog() != null) sql.append(part(q, t.getCatalog())).append('.');
            if (t.getSchema() != null) sql.append(part(q, t.getSchema())).append('.');
            sql.append(part(q, t.getTable()));
            delegate = new JdbcQuerySource(p, sql.toString(), Collections.<Object>emptyList(), fetchSize);
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot inspect JDBC identifier quoting", e);
        }
    }

    private static String part(String q, String s) {
        if (!s.matches("[A-Za-z0-9_$#]+")) throw new IllegalArgumentException("unsafe identifier: " + s);
        return q.isEmpty() ? s : q + s.replace(q, q + q) + q;
    }

    public RecordSchema schema() {
        return tableSchema;
    }

    public void read(BatchConsumer c) {
        delegate.read(batch -> {
            List<DataRecord> records = new ArrayList<DataRecord>(batch.size());
            for (DataRecord record : batch.getRecords()) {
                records.add(new DataRecord(tableSchema, record.getValues()));
            }
            c.accept(new RecordBatch(tableSchema, records));
        });
    }

    public void close() {
        delegate.close();
    }
}
