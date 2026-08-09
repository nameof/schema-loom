package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.dialect.*;
import io.github.nameof.schemaloom.metadata.*;

import java.sql.*;
import java.util.*;

public final class JdbcTableSource implements Source {
    private final JdbcQuerySource delegate;
    private final RecordSchema tableSchema;
    private final ConnectionProvider provider;
    private final QualifiedTableName table;
    private final DatabaseDialect dialect;

    public JdbcTableSource(DatabaseConnectionInfo info, String table) {
        this(info, table, 1000);
    }

    public JdbcTableSource(DatabaseConnectionInfo info, String table, int fetchSize) {
        this(JdbcConnectionFactory.open(info), info.table(table), fetchSize, new DialectRegistry().get(info.getDatabaseType()));
    }

    public JdbcTableSource(DatabaseConnectionInfo info, String table, JdbcDriverLoader loader) {
        this(info, table, loader, 1000);
    }

    public JdbcTableSource(DatabaseConnectionInfo info, String table, JdbcDriverLoader loader, int fetchSize) {
        this(JdbcConnectionFactory.open(info, loader), info.table(table), fetchSize, new DialectRegistry().get(info.getDatabaseType()));
    }

    /** 读取表元数据并构造带正确标识符引用的 SELECT 查询委托。 */
    private JdbcTableSource(ConnectionProvider p, QualifiedTableName t, int fetchSize, DatabaseDialect dialect) {
        provider = p;
        table = t;
        this.dialect = dialect;
        TableInfo tableInfo = new DatabaseMetadataService().getTable(p, t);
        tableSchema = tableInfo.getSchema();
        String sql = "SELECT * FROM " + dialect.quote(t);
        delegate = new JdbcQuerySource(p, sql, Collections.<Object>emptyList(), fetchSize);
    }

    public RecordSchema schema() {
        return tableSchema;
    }

    public long count() {
        try {
            PreparedStatement statement = provider.getConnection().prepareStatement("SELECT COUNT(*) FROM " + dialect.quote(table));
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
