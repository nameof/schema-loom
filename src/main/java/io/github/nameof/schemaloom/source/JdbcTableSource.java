package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.dialect.*;
import io.github.nameof.schemaloom.metadata.*;

import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * JDBC 表或视图的数据 Source。需要筛选、联表或聚合时，应使用参数化的 {@link JdbcQuerySource}。
 *
 * <p>视图支持：VIEW 在这里是只读的数据来源，它的输出列 Schema 可以传给
 * {@code JdbcTableTarget}，但不会复制 VIEW 背后的基础表结构、索引或约束。（支持跨数据库类型VIEW etl；因为迁移的是 VIEW 查询结果，不会复制或改写 VIEW SQL。）
 *
 * <p>当它作为 VIEW Source 使用时，目标行为由 {@code JdbcTableTarget} 决定：
 * 目标不存在则按 VIEW 输出 Schema 创建普通表，目标已存在则按目标模式校验并写入。</p>
 */
public final class JdbcTableSource implements Source {
    private final DatabaseConnectionInfo info;
    private final QualifiedTableName table;
    private final int fetchSize;
    private final Supplier<ConnectionProvider> providerSupplier;
    private final DatabaseDialect dialect;
    private volatile JdbcQuerySource delegate;
    private volatile RecordSchema tableSchema;
    private volatile ConnectionProvider provider;
    private volatile boolean closed;

    /**
     * 不传JdbcDriverLoader，默认方式创建ConnectionProvider
     */
    public JdbcTableSource(DatabaseConnectionInfo info, String table) {
        this(info, table, 1000);
    }

    /**
     * 不传JdbcDriverLoader，默认方式创建ConnectionProvider
     */
    public JdbcTableSource(DatabaseConnectionInfo info, String table, int fetchSize) {
        this(info, table, fetchSize, () -> JdbcConnectionFactory.open(info));
    }

    /**
     * 自定义JdbcDriverLoader创建ConnectionProvider
     */
    public JdbcTableSource(DatabaseConnectionInfo info, String table, JdbcDriverLoader loader) {
        this(info, table, loader, 1000);
    }

    /**
     * 自定义JdbcDriverLoader创建ConnectionProvider
     */
    public JdbcTableSource(DatabaseConnectionInfo info, String table, JdbcDriverLoader loader, int fetchSize) {
        this(info, table, fetchSize, providerSupplier(info, loader));
    }

    private static Supplier<ConnectionProvider> providerSupplier(DatabaseConnectionInfo info, JdbcDriverLoader loader) {
        if (loader == null) throw new IllegalArgumentException("jdbc driver loader is required");
        return () -> JdbcConnectionFactory.open(info, loader);
    }

    /**
     * 读取表或视图元数据，并构造带正确标识符引用的 SELECT 查询委托。
     * 视图只作为只读数据源，其输出 Schema 可供 JdbcTableTarget 创建普通目标表。
     */
    private JdbcTableSource(DatabaseConnectionInfo info, String tableName, int fetchSize,
                            Supplier<ConnectionProvider> providerSupplier) {
        if (info == null) throw new IllegalArgumentException("database connection info is required");
        this.info = info;
        this.table = info.table(tableName);
        this.fetchSize = fetchSize;
        this.providerSupplier = providerSupplier;
        this.dialect = new DialectRegistry().get(info.getDatabaseType());
    }

    private synchronized ConnectionProvider ensureProvider() {
        if (closed) throw new SchemaLoomException("source is closed");
        if (provider != null) return provider;
        ConnectionProvider opened = null;
        try {
            opened = providerSupplier.get();
            delegate = new JdbcQuerySource(opened, "SELECT * FROM " + dialect.quote(table),
                    Collections.<Object>emptyList(), fetchSize);
            provider = opened;
            return opened;
        } catch (RuntimeException e) {
            if (opened != null) opened.close();
            throw e;
        }
    }

    private synchronized RecordSchema ensureSchema() {
        if (tableSchema == null) {
            TableInfo tableInfo = new DatabaseMetadataService().getTable(ensureProvider(), table);
            tableSchema = tableInfo.getSchema();
        }
        return tableSchema;
    }

    public RecordSchema schema() {
        return ensureSchema();
    }

    public long count() {
        try {
            PreparedStatement statement = ensureProvider().getConnection().prepareStatement("SELECT COUNT(*) FROM " + dialect.quote(table));
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
            throw new SchemaLoomException("cannot count JDBC table: " + e.getMessage(), e);
        }
    }

    /** 读取委托批次，并将记录绑定到表 Schema 后再交给调用方。 */
    public void read(BatchConsumer c) {
        final RecordSchema schema = ensureSchema();
        delegate.read(batch -> {
            List<DataRecord> records = new ArrayList<DataRecord>(batch.size());
            // 委托返回的记录可能携带不同的 Schema，这里统一替换为表的正式 Schema。
            for (DataRecord record : batch.getRecords()) {
                records.add(new DataRecord(schema, record.getValues()));
            }
            c.accept(new RecordBatch(schema, records));
        });
    }

    public synchronized void close() {
        closed = true;
        if (provider != null) provider.close();
    }
}
