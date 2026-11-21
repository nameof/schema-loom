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
 * {@code REPLACE} 会先写入 {@code <目标表>_tmp}，在关闭时通过
 * {@link #close(boolean) close(commit)} 完成换表（原表先改名为 {@code <目标表>_old}，
 * 临时表接位成功后再删除备份）；commit 为 false（任务失败或不完整）或本目标发生过异常时，
 * 只清理临时表、保留原目标表。</p>
 *
 * <p>VIEW：只能作为数据只读来源，不能作为 Target 的写入对象。若Target对象已经是 VIEW，准备阶段会失败；
 * 需要在目标库创建 VIEW 定义时，应使用独立的 {@code JdbcViewMigrationTask}，而不是把 VIEW 当普通表写入数据。</p>
 */
public final class JdbcTableTarget implements Target, WriteStatisticsProvider {
    private static final Logger log = LoggerFactory.getLogger(JdbcTableTarget.class);
    private final ConnectionProvider provider;
    /** 连接配置，用于按方言推导元数据查询的命名空间范围（见 MetadataQuery#fromConnectionInfo）。 */
    private final DatabaseConnectionInfo connectionInfo;
    private final QualifiedTableName table;
    private final DatabaseDialect dialect;
    private final MetadataErrorPolicy metadataErrorPolicy;
    private final JdbcExecutionAdapter execution;
    private final List<EtlError> preparationErrors = new ArrayList<EtlError>();
    private RecordSchema schema;
    private RecordSchema writeSchema;
    private TableInfo tableInfo;
    private boolean prepared;
    private boolean replaceMode;
    /** 本目标发生过异常（准备或写入阶段），close 时禁止提交，防止部分数据替换原表。 */
    private boolean writeFailed;
    /** 实际写入的表（REPLACE 模式下是临时表）是否存在需要显式插入的自增列，决定是否需要 IDENTITY_INSERT。 */
    private boolean writeTableHasIdentity;
    private QualifiedTableName writeTable;
    private boolean temporaryCreated;
    private long writtenRows;

    JdbcTableTarget(ConnectionProvider p, DatabaseConnectionInfo info, QualifiedTableName table, DatabaseType type) {
        this(p, info, table, type, MetadataErrorPolicy.IGNORE);
    }

    JdbcTableTarget(ConnectionProvider p, DatabaseConnectionInfo info, QualifiedTableName table, DatabaseType type, MetadataErrorPolicy policy) {
        provider = p;
        connectionInfo = info;
        this.table = table;
        this.dialect = new DialectRegistry().get(type);
        this.metadataErrorPolicy = policy == null ? MetadataErrorPolicy.IGNORE : policy;
        this.execution = new JdbcExecutionAdapter(p);
    }

    public JdbcTableTarget(DatabaseConnectionInfo info, String table) {
        this(info, table, null);
    }

    public JdbcTableTarget(DatabaseConnectionInfo info, String table, JdbcDriverLoader loader) {
        this(openValidated(info, table, loader), info, info.table(table), info.getDatabaseType());
    }

    public JdbcTableTarget(DatabaseConnectionInfo info, String table, JdbcDriverLoader loader, MetadataErrorPolicy policy) {
        this(openValidated(info, table, loader), info, info.table(table), info.getDatabaseType(), policy);
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
        writeFailed = false;
        temporaryCreated = false;
        tableInfo = descriptor.getTableInfo();
        writeSchema = writableSchema(schema, tableInfo);
        log.info("JDBC目标准备开始 runId={} table={} mode={}", LoggingSupport.currentRunId(), table.getTable(), mode);
        validateCapabilities(schema);
        try {
            DatabaseMetadataService metadata = new DatabaseMetadataService();
            List<TableInfo> tables = metadata.listTables(provider, MetadataQuery.fromConnectionInfo(connectionInfo, table));
            TableInfo existingTable = tables.isEmpty() ? null : tables.get(0);
            boolean exists = existingTable != null;
            if (exists && existingTable.isView())
                throw new SchemaLoomException("JDBC table target cannot write to a view: " + table.getTable());
            // 确定实际写入的表名（替换模式使用临时表）
            writeTable = replaceMode ? temporaryTable() : table;
            String q = dialect.quote(writeTable);
            if (replaceMode) {
                dropIfPresent(writeTable, "drop temporary target table");
                temporaryCreated = true;
                exists = false;
            }
            // 表不存在则创建（含注释和索引），已存在则校验结构兼容性
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
            // 自增列的显式插入取决于「实际写入的那张表」是否含自增列：
            // APPEND 到已存在的目标表时以目标表为准（目标可能没有自增列），
            // 目标表或临时表由源表结构新建时才以源表为准。
            writeTableHasIdentity = hasIdentityInWriteColumns(exists ? existingTable : tableInfo);
            prepared = true;
            log.info("JDBC目标准备完成 runId={} table={} mode={} temporary={}", LoggingSupport.currentRunId(), table.getTable(), mode, replaceMode);
            List<EtlError> errors = new ArrayList<EtlError>(preparationErrors);
            preparationErrors.clear();
            return errors;
        } catch (RuntimeException e) {
            writeFailed = true;
            throw e;
        }
    }

    private QualifiedTableName temporaryTable() {
        return new QualifiedTableName(table.getCatalog(), table.getSchema(), table.getTable() + "_tmp");
    }

    /** REPLACE 提交时原表的临时落脚名：换表成功后删除，失败时它就是原数据的保底副本。 */
    private QualifiedTableName backupTable() {
        return new QualifiedTableName(table.getCatalog(), table.getSchema(), table.getTable() + "_old");
    }

    private boolean exists(QualifiedTableName name) {
        return !new DatabaseMetadataService().listTables(provider,
                MetadataQuery.fromConnectionInfo(connectionInfo, name)).isEmpty();
    }

    private void dropIfPresent(QualifiedTableName name, String stage) {
        if (exists(name))
            execution.execute(stage, dialect.dropTableSql(dialect.quote(name)));
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
            // 降级不能静默：时区等成分会被丢弃，必须让使用者看见
            LogicalType degraded = mapping.degradesTo();
            if (degraded != null)
                log.warn("目标方言不支持该类型，已降级写入 runId={} field={} from={} to={}",
                        LoggingSupport.currentRunId(), field.getName(), field.getLogicalType(), degraded);
        }
    }

    public BatchWriteResult write(RecordBatch b) {
        if (!prepared)
            throw new SchemaLoomException("target is not prepared");
        try {
            final RecordBatch batch = b;
            String identityOn = identityInsertSql(true);
            String identityOff = identityInsertSql(false);
            if (identityOn != null)
                execution.execute("enable identity insert", identityOn); // 启用自增列显式插入
            try {
                execution.batchUpdate("write target batch", dialect.insertSql(dialect.quote(writeTable), writeSchema),
                        new JdbcExecutionAdapter.BatchSetter() {
                        public void setValues(PreparedStatement statement, int row) throws SQLException {
                            DataRecord record = batch.getRecords().get(row);
                            for (int column = 0; column < writeSchema.getFields().size(); column++)
                                setValue(statement, column + 1, writeSchema.getFields().get(column),
                                        record.get(schema.indexOf(writeSchema.getFields().get(column).getName())));
                        }

                        public int getBatchSize() { return batch.size(); }
                        });
            } finally {
                if (identityOff != null) execution.execute("disable identity insert", identityOff);
            }
            incrementWrittenRows(b.size());
            log.debug("JDBC目标批次写入完成 runId={} table={} rows={}",
                    LoggingSupport.currentRunId(), writeTable.getTable(), b.size());
            return new BatchWriteResult(b.size(), 0);
        } catch (RuntimeException e) {
            writeFailed = true;
            log.warn("JDBC目标批次写入失败 runId={} table={} rows={} message={}",
                    LoggingSupport.currentRunId(), writeTable.getTable(), b.size(), LoggingSupport.message(e));
            throw e;
        }
    }

    /**
     * 当写入字段包含自增列时，返回启用/禁用 identity insert 的 SQL；否则返回 null
     */
    private String identityInsertSql(boolean on) {
        if (!writeTableHasIdentity || writeSchema == null || writeTable == null) return null;
        return on ? dialect.identityInsertOnSql(dialect.quote(writeTable))
                : dialect.identityInsertOffSql(dialect.quote(writeTable));
    }

    /**
     * 判定写入列集合中是否包含自增列。
     *
     * <p>自增列若被 {@link #writableSchema} 过滤掉（例如是生成列），就不存在显式插入，
     * 此时开启 IDENTITY_INSERT 反而会在没有自增列的表上直接报错。</p>
     */
    private boolean hasIdentityInWriteColumns(TableInfo metadata) {
        if (metadata == null || writeSchema == null) return false;
        for (ColumnInfo column : metadata.getColumns()) {
            if (!column.isAutoIncremented()) continue;
            for (FieldSchema field : writeSchema.getFields())
                if (field.getName().equalsIgnoreCase(column.getName())) return true;
        }
        return false;
    }

    /**
     * 过滤掉目标表中的生成列（computed/generated column），返回实际可写入的 schema
     */
    private RecordSchema writableSchema(RecordSchema source, TableInfo metadata) {
        if (metadata == null) return source;
        List<FieldSchema> writable = new ArrayList<>();
        for (FieldSchema field : source.getFields()) {
            boolean generated = false;
            for (ColumnInfo column : metadata.getColumns()) {
                if (column.getName().equalsIgnoreCase(field.getName())) {
                    generated = column.isGenerated();
                    break;
                }
            }
            if (!generated) writable.add(field);
        }
        if (writable.isEmpty()) throw new SchemaLoomException("target has no writable columns");
        return new RecordSchema(writable);
    }

    private void setValue(PreparedStatement ps, int index, FieldSchema field, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(index, nullSqlType(field));
            return;
        }
        JdbcValueCodec.write(ps, index, field, value, dialect);
    }

    /**
     * null 绑定用「逻辑类型在目标方言下的类型码」，不能沿用源列的类型码：
     * 源列类型码是源库的概念，目标驱动未必认识（实测 PostgreSQL 源列的 NVARCHAR(-9)
     * 写到 VARCHAR 列报「无法确定参数类型」、Oracle 目标的 OTHER(1111) 报 ORA-17004），
     * 而且源列类型与建表时生成的目标列类型本来就是两回事。
     * <p>
     * 方言把逻辑类型降级时必须跟着降级，否则 DB2 会因 2013（TIME_WITH_TIMEZONE）
     * 报「无法识别的 JDBC 类型」（ERRORCODE=-4228）。
     */
    private int nullSqlType(FieldSchema field) {
        DatabaseTypeMapping mapping = dialect.mapping(field.getLogicalType());
        LogicalType target = mapping == null ? null : mapping.degradesTo();
        return LogicalTypeCatalog.get(target == null ? field.getLogicalType() : target).jdbcSqlType();
    }

    /** 直接调用方 close 即提交（AutoCloseable 契约不变）。 */
    public void close() {
        close(true);
    }

    /**
     * 关闭目标。REPLACE 模式下根据 commit 决定是否换表提交。
     *
     * <p>commit 为 false（任务失败或不完整）、本目标发生过异常（writeFailed）、或未成功 prepare 时，
     * 只删除临时表、保留原目标表；否则执行「原表改名备份 → 临时表接位 → 删除备份」的两阶段换表，
     * 任何一步失败都不会同时丢掉原表与新数据。</p>
     *
     * <p>极端场景说明：prepare 抛出非 RuntimeException 的 Throwable（如 Error）时 writeFailed
     * 不会置位，捕获后仍以提交语义关闭的直接调用方应自行确认写入完整性。</p>
     */
    public void close(boolean commit) {
        try {
            if (replaceMode && temporaryCreated) {
                if (commit && !writeFailed) {
                    swapReplace();
                } else {
                    try {
                        dropIfPresent(writeTable, "drop temporary target table");
                        temporaryCreated = false;
                        log.info("REPLACE 未提交，已清理临时表 runId={} table={}", LoggingSupport.currentRunId(), table.getTable());
                    } catch (RuntimeException e) {
                        log.warn("REPLACE 未提交，临时表清理失败 runId={} temporary={} message={}",
                                LoggingSupport.currentRunId(), writeTable.getTable(), LoggingSupport.message(e));
                    }
                }
            }
        } finally {
            provider.close();
        }
    }

    /**
     * 两阶段换表：原表改名 {@code _old} → 临时表接位 → 删除 {@code _old}。
     *
     * <p>第一步失败时原表与临时表都还在；第二步失败时把 {@code _old} 改回原名、临时表保留；
     * 第三步失败只残留一份备份，不影响已提交的数据。任何一步都不先删原表，避免「双丢」。</p>
     */
    private void swapReplace() {
        QualifiedTableName backup = backupTable();
        if (exists(backup))
            throw new SchemaLoomException("备份表已存在，请确认其数据后手动删除或改名再重试: " + backup.getTable());
        boolean hasTarget = exists(table);
        if (hasTarget)
            execution.execute("rename replaced target table",
                    dialect.renameTableSql(dialect.quote(table), dialect.quote(backup)));
        try {
            execution.execute("rename replacement target table",
                    dialect.renameTableSql(dialect.quote(writeTable), dialect.quote(table)));
        } catch (RuntimeException e) {
            if (hasTarget) {
                try {
                    execution.execute("restore replaced target table",
                            dialect.renameTableSql(dialect.quote(backup), dialect.quote(table)));
                    log.warn("REPLACE 切换失败，原表已恢复 runId={} temporary={}（临时表保留，可人工改用）",
                            LoggingSupport.currentRunId(), writeTable.getTable());
                } catch (RuntimeException r) {
                    log.error("REPLACE 切换失败且原表回滚失败，需人工恢复 runId={} backup={} temporary={} message={}",
                            LoggingSupport.currentRunId(), backup.getTable(), writeTable.getTable(), LoggingSupport.message(r));
                }
            }
            throw e;
        }
        temporaryCreated = false;
        log.info("JDBC目标替换完成 runId={} table={}", LoggingSupport.currentRunId(), table.getTable());
        if (hasTarget) {
            try {
                execution.execute("drop replaced target table", dialect.dropTableSql(dialect.quote(backup)));
            } catch (RuntimeException e) {
                log.warn("REPLACE 已提交，但备份表删除失败 runId={} backup={} message={}，可手动删除",
                        LoggingSupport.currentRunId(), backup.getTable(), LoggingSupport.message(e));
            }
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
