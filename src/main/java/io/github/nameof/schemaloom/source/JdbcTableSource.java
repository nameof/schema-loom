package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.metadata.*;

import java.sql.*;
import java.util.*;

public final class JdbcTableSource implements Source {
    private final JdbcQuerySource delegate;
    private final RecordSchema tableSchema;
    private final ConnectionProvider provider;
    private final String qualifiedTable;

    public JdbcTableSource(ConnectionProvider p, QualifiedTableName t) {
        this(p, t, 1000);
    }

    /** 读取表元数据并构造带正确标识符引用的 SELECT 查询委托。 */
    public JdbcTableSource(ConnectionProvider p, QualifiedTableName t, int fetchSize) {
        provider = p;
        try {
            TableInfo tableInfo = new DatabaseMetadataService().getTable(p, t);
            tableSchema = tableInfo.getSchema();
            String qmark = p.getConnection().getMetaData().getIdentifierQuoteString();
            String q = (qmark == null || qmark.trim().isEmpty()) ? "" : qmark.trim();
            // 按 catalog.schema.table 顺序拼接，并统一交给 part 校验和转义各段标识符。
            StringBuilder sql = new StringBuilder("SELECT * FROM ");
            if (t.getCatalog() != null) sql.append(part(q, t.getCatalog())).append('.');
            if (t.getSchema() != null) sql.append(part(q, t.getSchema())).append('.');
            sql.append(part(q, t.getTable()));
            qualifiedTable = sql.substring("SELECT * FROM ".length());
            delegate = new JdbcQuerySource(p, sql.toString(), Collections.<Object>emptyList(), fetchSize);
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot inspect JDBC identifier quoting", e);
        }
    }

    /** 校验标识符字符集，并在数据库要求引用时转义引用符。 */
    private static String part(String q, String s) {
        if (!s.matches("[A-Za-z0-9_$#]+")) throw new IllegalArgumentException("unsafe identifier: " + s);
        return q.isEmpty() ? s : q + s.replace(q, q + q) + q;
    }

    public RecordSchema schema() {
        return tableSchema;
    }

    public long count() {
        try {
            PreparedStatement statement = provider.getConnection().prepareStatement("SELECT COUNT(*) FROM " + qualifiedTable);
            try {
                ResultSet result = statement.executeQuery();
                try {
                    if (!result.next()) throw new SQLException("count query returned no row");
                    return result.getLong(1);
                } finally {
                    result.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot count JDBC table", e);
        }
    }

    /** 读取委托批次，并将记录绑定到表 Schema 后再交给调用方。 */
    public void read(BatchConsumer c) {
        delegate.read(batch -> {
            List<DataRecord> records = new ArrayList<DataRecord>(batch.size());
            // 委托返回的记录可能携带不同的 Schema，这里统一替换为表的正式 Schema。
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
