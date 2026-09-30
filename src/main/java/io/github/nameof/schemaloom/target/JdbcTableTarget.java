package io.github.nameof.schemaloom.target;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.codec.JdbcValueCodec;
import io.github.nameof.schemaloom.dialect.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.execution.JdbcExecutionAdapter;
import io.github.nameof.schemaloom.internal.LoggingSupport;
import io.github.nameof.schemaloom.metadata.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * JDBC 普通表 Target。
 *
 * <p>目标表不存在时，{@link #prepare} 会依据 Source
 * Schema 自动生成普通表；目标已存在时，{@code APPEND} 只在结构兼容时追加，
 * {@code REPLACE} 会先写入 {@code <目标表>_tmp}，成功后通过 rename 切换；失败时保留原目标表。
 * </p>
 *
 * <p>VIEW：只能作为数据只读来源，不能作为 Target 的写入对象。若Target对象已经是 VIEW，准备阶段会失败；
 * 需要在目标库创建 VIEW 定义时，应使用独立的 {@code JdbcViewMigrationTask}，而不是把 VIEW 当普通表写入数据。</p>
 */
public final class JdbcTableTarget implements Target, WriteStatisticsProvider {
    private static final Logger log = LoggerFactory.getLogger(JdbcTableTarget.class);
    private final ConnectionProvider provider;
    private final QualifiedTableName table;
    private final DatabaseDialect dialect;
    private final MetadataErrorPolicy metadataErrorPolicy;
    private final JdbcExecutionAdapter execution;
    private final List<EtlError> preparationErrors = new ArrayList<EtlError>();
    private RecordSchema schema;
    private TableInfo tableInfo;
    private boolean prepared;
    private boolean replaceMode;
    private boolean targetWriteFailed;
    private QualifiedTableName writeTable;
    private boolean temporaryCreated;
    private long writtenRows;

    JdbcTableTarget(ConnectionProvider p, QualifiedTableName table, DatabaseType type) {
        this(p, table, type, MetadataErrorPolicy.IGNORE);
    }

    JdbcTableTarget(ConnectionProvider p, QualifiedTableName table, DatabaseType type, MetadataErrorPolicy policy) {
        provider = p;
        this.table = table;
        this.dialect = new DialectRegistry().get(type);
        this.metadataErrorPolicy = policy == null ? MetadataErrorPolicy.IGNORE : policy;
        this.execution = new JdbcExecutionAdapter(p);
    }

    public JdbcTableTarget(DatabaseConnectionInfo info, String table) {
        this(info, table, null);
    }

    public JdbcTableTarget(DatabaseConnectionInfo info, String table, JdbcDriverLoader loader) {
        this(openValidated(info, table, loader), info.table(table), info.getDatabaseType());
    }

    public JdbcTableTarget(DatabaseConnectionInfo info, String table, JdbcDriverLoader loader, MetadataErrorPolicy policy) {
        this(openValidated(info, table, loader), info.table(table), info.getDatabaseType(), policy);
    }

    private static ConnectionProvider openValidated(DatabaseConnectionInfo info, String table, JdbcDriverLoader loader) {
        if (info == null) throw new IllegalArgumentException("database connection info is required");
        info.table(table);
        return loader == null ? JdbcConnectionFactory.open(info) : JdbcConnectionFactory.open(info, loader);
    }

    public List<EtlError> prepare(SchemaDescriptor descriptor, TargetMode mode) {
        if (descriptor == null) throw new IllegalArgumentException("schema descriptor is required");
        schema = descriptor.getSchema();
        replaceMode = mode == TargetMode.REPLACE;
        targetWriteFailed = false;
        temporaryCreated = false;
        tableInfo = descriptor.getTableInfo();
        log.info("JDBC目标准备开始 runId={} table={} mode={}", LoggingSupport.currentRunId(), table.getTable(), mode);
        validateCapabilities(schema);
        try {
            DatabaseMetadataService metadata = new DatabaseMetadataService();
            List<TableInfo> tables = metadata.listTables(provider, new MetadataQuery(table.getCatalog(), table.getSchema(), table.getTable()));
            TableInfo existingTable = tables.isEmpty() ? null : tables.get(0);
            boolean exists = existingTable != null;
            if (exists && existingTable.isView())
                throw new SchemaLoomException("JDBC table target cannot write to a view: " + table.getTable());
            // Determine write table name
            writeTable = replaceMode ? temporaryTable() : table;
            String q = dialect.quote(writeTable);
            if (replaceMode) {
                dropTemporaryIfPresent();
                temporaryCreated = true;
                exists = false;
            }
            // Create or validate table
            if (!exists) {
                execution.execute("create target table", tableInfo == null
                        ? dialect.createTableSql(q, schema) : dialect.createTableSql(q, tableInfo));
                if (tableInfo != null) {
                    executeMetadataSql(dialect.commentSql(q, tableInfo), "注释");
                    executeMetadataSql(dialect.indexSql(q, tableInfo.getIndexes()), "索引");
                }
            } else {
                validateAppend(existingTable, schema);
                if (tableInfo != null) migrateIndexes(q, tableInfo, existingTable);
            }
            prepared = true;
            log.info("JDBC目标准备完成 runId={} table={} mode={} temporary={}", LoggingSupport.currentRunId(), table.getTable(), mode, replaceMode);
            List<EtlError> errors = new ArrayList<EtlError>(preparationErrors);
            preparationErrors.clear();
            return errors;
        } catch (RuntimeException e) {
            targetWriteFailed = true;
            throw e;
        }
    }

    private QualifiedTableName temporaryTable() {
        return new QualifiedTableName(table.getCatalog(), table.getSchema(), table.getTable() + "_tmp");
    }

    private void dropTemporaryIfPresent() {
        List<TableInfo> tables = new DatabaseMetadataService().listTables(provider,
                new MetadataQuery(table.getCatalog(), table.getSchema(), table.getTable() + "_tmp"));
        if (!tables.isEmpty()) execution.execute("drop temporary target table", dialect.dropTableSql(dialect.quote(writeTable)));
    }

    /** 按名称和完整定义比较索引；等价索引直接跳过，冲突按结构策略处理。 */
    private void migrateIndexes(String q, TableInfo source, TableInfo target) {
        for (IndexInfo expected : source.getIndexes()) {
            if (expected.getName() == null || expected.getName().trim().isEmpty() || expected.getColumns().isEmpty()) continue;
            IndexInfo equivalent = null;
            IndexInfo sameName = null;
            for (IndexInfo actual : target.getIndexes()) {
                if (sameDefinition(expected, actual)) equivalent = actual;
                if (actual.getName() != null && actual.getName().equalsIgnoreCase(expected.getName())) sameName = actual;
            }
            if (equivalent != null) continue;
            if (sameName != null) {
                handleMetadataError("索引冲突: " + expected.getName(), new SchemaLoomException("目标索引定义不一致"));
                continue;
            }
            executeMetadataSql(indexSql(expected, q), "索引");
        }
    }

    private List<String> indexSql(IndexInfo index, String tableName) {
        return dialect.indexSql(tableName, Collections.singletonList(index));
    }

    private boolean sameDefinition(IndexInfo a, IndexInfo b) {
        if (a.isUnique() != b.isUnique() || a.getColumns().size() != b.getColumns().size()) return false;
        for (int i = 0; i < a.getColumns().size(); i++)
            if (!a.getColumns().get(i).equalsIgnoreCase(b.getColumns().get(i))) return false;
        return true;
    }

    private void executeMetadataSql(List<String> sql, String stage) {
        for (String statement : sql) {
            try {
                execution.execute(stage + "迁移", statement);
            } catch (SchemaLoomException e) {
                handleMetadataError(stage + "迁移失败", e);
            }
        }
    }

    private void handleMetadataError(String message, Throwable error) {
        log.warn("JDBC目标元数据迁移失败 runId={} table={} policy={} message={}",
                LoggingSupport.currentRunId(), table.getTable(), metadataErrorPolicy, LoggingSupport.message(error));
        if (metadataErrorPolicy == MetadataErrorPolicy.FAIL)
            throw new SchemaLoomException(message, error);
        preparationErrors.add(new EtlError(0, "metadata", new SchemaLoomException(message, error)));
    }


    private void validateCapabilities(RecordSchema source) {
        for (FieldSchema field : source.getFields()) {
            DatabaseTypeMapping mapping = dialect.mapping(field.getLogicalType());
            if (mapping == null || !mapping.isSupported())
                throw new SchemaLoomException("target does not support logical type " + field.getLogicalType()
                        + " for field '" + field.getName() + "'");
        }
    }

    public BatchWriteResult write(RecordBatch b) {
        if (!prepared)
            throw new SchemaLoomException("target is not prepared");
        try {
            final RecordBatch batch = b;
            execution.batchUpdate("write target batch", dialect.insertSql(dialect.quote(writeTable), schema),
                    new JdbcExecutionAdapter.BatchSetter() {
                        public void setValues(PreparedStatement statement, int row) throws SQLException {
                            DataRecord record = batch.getRecords().get(row);
                            for (int column = 0; column < schema.getFields().size(); column++)
                                setValue(statement, column + 1, schema.getFields().get(column), record.get(column));
                        }

                        public int getBatchSize() { return batch.size(); }
                    });
            incrementWrittenRows(b.size());
            log.debug("JDBC目标批次写入完成 runId={} table={} rows={}",
                    LoggingSupport.currentRunId(), writeTable.getTable(), b.size());
            return new BatchWriteResult(b.size(), 0);
        } catch (RuntimeException e) {
            targetWriteFailed = true;
            log.warn("JDBC目标批次写入失败 runId={} table={} rows={} message={}",
                    LoggingSupport.currentRunId(), writeTable.getTable(), b.size(), LoggingSupport.message(e));
            throw e;
        }
    }

    private void setValue(PreparedStatement ps, int index, FieldSchema field, Object value) throws SQLException {
        if (value == null && tableInfo != null) {
            for (ColumnInfo column : tableInfo.getColumns()) {
                if (column.getName().equalsIgnoreCase(field.getName())) {
                    ps.setNull(index, column.getJdbcType());
                    return;
                }
            }
        }
        JdbcValueCodec.write(ps, index, field, value);
    }

    public void close() {
        try {
            // Replace mode: drop temporary table and rename target table to final name.
            if (replaceMode && temporaryCreated) {
                try {
                    if (targetWriteFailed || !prepared) {
                        dropTemporaryIfPresent();
                    } else {
                        List<TableInfo> tables = new DatabaseMetadataService().listTables(provider,
                                new MetadataQuery(table.getCatalog(), table.getSchema(), table.getTable()));
                        if (!tables.isEmpty())
                            execution.execute("drop replaced target table", dialect.dropTableSql(dialect.quote(table)));
                        execution.execute("rename replacement target table", dialect.renameTableSql(dialect.quote(writeTable), dialect.quote(table)));
                        log.info("JDBC目标替换完成 runId={} table={}", LoggingSupport.currentRunId(), table.getTable());
                    }
                } catch (RuntimeException e) {
                    targetWriteFailed = true;
                    try { dropTemporaryIfPresent(); } catch (RuntimeException ignored) { }
                    throw e;
                }
            }
        } finally {
            provider.close();
        }
    }

    public synchronized WriteStatistics getWriteStatistics() {
        return new WriteStatistics(writtenRows, 0, 0, Collections.<String, Long>emptyMap());
    }

    private synchronized void incrementWrittenRows(long count) { writtenRows += count; }

    /**
     * 校验已存在的目标表是否可以安全接收源 Schema。
     * APPEND 不修改目标表，因此所有不兼容情况都必须在这里提前失败。
     */
    private void validateAppend(TableInfo tableInfo, RecordSchema source) {
        Map<String, ExistingColumn> target = new LinkedHashMap<String, ExistingColumn>();
        for (ColumnInfo column : tableInfo.getColumns()) target.put(column.getName().toLowerCase(Locale.ENGLISH), new ExistingColumn(
                column.getName(), column.getLogicalType(), column.getLength() == null ? 0 : column.getLength(),
                column.getScale() == null ? 0 : column.getScale(), column.isNullable(), column.getDefaultValue(),
                column.isAutoIncremented(), column.isGenerated()));
        for (FieldSchema field : source.getFields()) {
            // 移除已匹配字段后，Map 中只剩源 Schema 未提供的目标字段。
            ExistingColumn existing = target.remove(field.getName().toLowerCase(Locale.ENGLISH));
            if (existing == null) throw new SchemaLoomException("target column missing: " + field.getName());
            if (field.isNullable() && !existing.nullable)
                throw new SchemaLoomException("nullable source cannot append to NOT NULL column: " + field.getName());
            if (!safe(field, existing))
                throw new SchemaLoomException("incompatible target column: " + field.getName());
        }
        for (ExistingColumn extra : target.values()) {
            // 额外的必填字段不在源 Schema 中，会导致 INSERT 失败。
            if (!extra.nullable && extra.defaultValue == null && !extra.autoGenerated && !extra.generated)
                throw new SchemaLoomException("target has required extra column: " + extra.name);
        }
    }

    /** 判断源字段写入现有目标字段时是否不会发生有损转换。 */
    private boolean safe(FieldSchema source, ExistingColumn target) {
        LogicalType from = source.getLogicalType(), to = target.type;
        if (from == to) {
            // 逻辑类型相同时，字符串容量和 DECIMAL 精度仍需单独校验。
            if (from == LogicalType.STRING && source.getLength() != null && target.size > 0 && source.getLength() > target.size) return false;
            if (from == LogicalType.DECIMAL && source.getPrecision() != null && target.size > 0 && source.getPrecision() > target.size) return false;
            return from != LogicalType.DECIMAL || source.getScale() == null || target.scale <= 0 || source.getScale() <= target.scale;
        }
        // 数值拓宽和 DATE -> TIMESTAMP 可以保留源值含义。
        if (from == LogicalType.INT16) return to == LogicalType.INT32 || to == LogicalType.INT64 || to == LogicalType.DECIMAL || to == LogicalType.FLOAT32 || to == LogicalType.FLOAT64;
        if (from == LogicalType.INT32) return to == LogicalType.INT64 || to == LogicalType.DECIMAL || to == LogicalType.FLOAT64;
        if (from == LogicalType.INT64) return to == LogicalType.DECIMAL || to == LogicalType.FLOAT64;
        if (from == LogicalType.FLOAT32) return to == LogicalType.FLOAT64 || to == LogicalType.DECIMAL;
        if (from == LogicalType.DATE) return to == LogicalType.TIMESTAMP;
        return false;
    }

    /** APPEND 校验所需的最小目标字段元数据。 */
    private static final class ExistingColumn {
        final String name;
        final LogicalType type;
        final int size, scale;
        final boolean nullable, autoGenerated, generated;
        final String defaultValue;

        ExistingColumn(String name, LogicalType type, int size, int scale, boolean nullable,
                       String defaultValue, boolean autoGenerated, boolean generated) {
            this.name = name; this.type = type; this.size = size; this.scale = scale;
            this.nullable = nullable; this.defaultValue = defaultValue;
            this.autoGenerated = autoGenerated; this.generated = generated;
        }
    }
}
