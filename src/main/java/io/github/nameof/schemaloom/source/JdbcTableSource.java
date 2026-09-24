package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.dialect.*;
import io.github.nameof.schemaloom.execution.JdbcExecutionAdapter;
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
public final class JdbcTableSource implements Source, ReadStatisticsProvider {
    public static final int MAX_PREVIEW_ROWS = 300;
    private final DatabaseConnectionInfo info;
    private final QualifiedTableName table;
    private final int fetchSize;
    private final Supplier<ConnectionProvider> providerSupplier;
    private final DatabaseDialect dialect;
    private final LargeFieldPolicy largeFieldPolicy;
    private volatile JdbcQuerySource delegate;
    private volatile RecordSchema tableSchema;
    private volatile TableInfo tableInfo;
    private volatile ConnectionProvider provider;
    private volatile JdbcExecutionAdapter execution;
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
        this(info, table, fetchSize, LargeFieldPolicy.defaults(), () -> JdbcConnectionFactory.open(info));
    }

    public JdbcTableSource(DatabaseConnectionInfo info, String table, int fetchSize, LargeFieldPolicy policy) {
        this(info, table, fetchSize, policy, () -> JdbcConnectionFactory.open(info));
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
        this(info, table, fetchSize, LargeFieldPolicy.defaults(), providerSupplier(info, loader));
    }

    public JdbcTableSource(DatabaseConnectionInfo info, String table, JdbcDriverLoader loader, int fetchSize, LargeFieldPolicy policy) {
        this(info, table, fetchSize, policy, providerSupplier(info, loader));
    }

    private static Supplier<ConnectionProvider> providerSupplier(DatabaseConnectionInfo info, JdbcDriverLoader loader) {
        if (loader == null) throw new IllegalArgumentException("jdbc driver loader is required");
        return () -> JdbcConnectionFactory.open(info, loader);
    }

    /**
     * 读取表或视图元数据，并构造带正确标识符引用的 SELECT 查询委托。
     * 视图只作为只读数据源，其输出 Schema 可供 JdbcTableTarget 创建普通目标表。
     */
    private JdbcTableSource(DatabaseConnectionInfo info, String tableName, int fetchSize, LargeFieldPolicy policy,
                            Supplier<ConnectionProvider> providerSupplier) {
        if (info == null) throw new IllegalArgumentException("database connection info is required");
        this.info = info;
        this.table = info.table(tableName);
        this.fetchSize = fetchSize;
        this.largeFieldPolicy = policy == null ? LargeFieldPolicy.defaults() : policy;
        this.providerSupplier = providerSupplier;
        this.dialect = new DialectRegistry().get(info.getDatabaseType());
    }

    private synchronized ConnectionProvider ensureProvider() {
        if (closed) throw new SchemaLoomException("source is closed");
        if (provider != null) return provider;
        ConnectionProvider opened = null;
        try {
            opened = providerSupplier.get();
            // 表 Source 复用查询 Source 的读取、批处理和大字段策略实现。
            delegate = new JdbcQuerySource(opened, "SELECT * FROM " + dialect.quote(table),
                    Collections.emptyList(), fetchSize, largeFieldPolicy);
            execution = new JdbcExecutionAdapter(opened);
            provider = opened;
            return opened;
        } catch (RuntimeException e) {
            if (opened != null) opened.close();
            throw e;
        }
    }

    private synchronized RecordSchema ensureSchema() {
        if (tableSchema == null) tableSchema = ensureTableInfo().toRecordSchema();
        return tableSchema;
    }

    /** 返回源表完整元数据，供 JDBC 表结构迁移使用。 */
    public synchronized TableInfo tableInfo() {
        return ensureTableInfo();
    }

    private synchronized TableInfo ensureTableInfo() {
        if (tableInfo == null)
            tableInfo = applyNullabilityTableInfo(new DatabaseMetadataService().getTable(ensureProvider(), table));
        return tableInfo;
    }

    /**
     * 对TableInfo进行转换，不同策略对字段可空的影响不同，所以改写原始TableInfo
     */
    private TableInfo applyNullabilityTableInfo(TableInfo source) {
        List<ColumnInfo> columns = new ArrayList<>();
        for (ColumnInfo column : source.getColumns()) {
            // 结构列和数据 Schema 同步调整，确保建表和写入看到一致的 nullable 定义。
            // 大字段策略可能产生 null，因此对外 Schema 必须反映这种可空性。
            boolean nullable = column.isNullable() || largeFieldPolicy.canReturnNull(column.getName(), column.getJdbcType(), column.getTypeName());
            FieldSchema field = new FieldSchema(column.getName(), column.getLogicalType(), nullable, column.getLength(),
                    column.getPrecision(), column.getScale());
            columns.add(new ColumnInfo(field, column.getTypeName(), column.getRemarks(),
                    column.getOrdinal(), column.getJdbcType(),
                    column.getDefaultValue(), column.getGeneratedExpression(), column.isAutoIncremented(), column.isGenerated()));
        }
        return new TableInfo(source.getName(), source.isView(), source.getType(), columns, source.getPrimaryKey(),
                source.getIndexes(), source.getForeignKeys(), source.getConstraints(), source.getRemarks());
    }

    public SchemaDescriptor schema() {
        return SchemaDescriptor.of(tableInfo());
    }

    public long count() {
        ensureProvider();
        return execution.queryForLong("count table", "SELECT COUNT(*) FROM " + dialect.quote(table));
    }

    /** 读取委托批次，并将记录绑定到表 Schema 后再交给调用方。 */
    public void read(BatchConsumer c) {
        final RecordSchema schema = ensureSchema();
        delegate.read(batch -> {
            List<DataRecord> records = new ArrayList<DataRecord>(batch.size());
            // 委托使用查询 Schema；这里换回表元数据生成的正式 Schema，保持引用一致性。
            // 委托返回的记录可能携带不同的 Schema，这里统一替换为表的正式 Schema。
            for (DataRecord record : batch.getRecords()) {
                records.add(new DataRecord(schema, record.getValues()));
            }
            c.accept(new RecordBatch(schema, records));
        });
    }

    /** 读取指定数量的表预览数据，最多 300 行。 */
    public List<DataRecord> preview(int maxRows) {
        if (maxRows < 1 || maxRows > MAX_PREVIEW_ROWS)
            throw new IllegalArgumentException("preview rows must be between 1 and " + MAX_PREVIEW_ROWS);
        final RecordSchema schema = ensureSchema();
        List<DataRecord> records = new ArrayList<DataRecord>();
        for (DataRecord record : delegate.readRows(maxRows))
            records.add(new DataRecord(schema, record.getValues()));
        return records;
    }

    public synchronized void close() {
        closed = true;
        if (provider != null) provider.close();
    }

    public ReadStatistics getReadStatistics() {
        // 统计实际读取委托；尚未打开连接时返回空快照。
        return delegate == null ? ReadStatistics.empty() : delegate.getReadStatistics();
    }
}
