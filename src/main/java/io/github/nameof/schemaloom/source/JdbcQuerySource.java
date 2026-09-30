package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.codec.JdbcValueCodec;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.execution.JdbcExecutionAdapter;
import io.github.nameof.schemaloom.internal.LoggingSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.*;
import java.util.*;

public final class JdbcQuerySource implements Source, ReadStatisticsProvider {
    private static final Logger log = LoggerFactory.getLogger(JdbcQuerySource.class);
    private final ConnectionProvider provider;
    private final String sql;
    private final List<Object> params;
    private final int fetchSize;
    private final LargeFieldPolicy largeFieldPolicy;
    private final JdbcExecutionAdapter execution;
    // key 为结果集字段名，value 为该字段被置空的行数。
    private final Map<String, Long> skippedFieldRows = new LinkedHashMap<>();
    // 只统计正式 read()，preview() 不污染任务统计。
    private long readRows;
    private RecordSchema schema;

    /** 受限读取结果集，供表预览复用查询源的类型映射逻辑。 */
    List<DataRecord> readRows(int maxRows) {
        RecordSchema sc = recordSchema();
        return execution.query("limited query", sql, params, fetchSize, maxRows,
                new JdbcExecutionAdapter.ResultSetHandler<List<DataRecord>>() {
                    public List<DataRecord> extractData(ResultSet resultSet) throws SQLException {
                        List<DataRecord> records = new ArrayList<DataRecord>();
                        ResultSetMetaData metadata = resultSet.getMetaData();
                        while (resultSet.next()) records.add(readRecord(resultSet, metadata, sc, true, false));
                        return records;
                    }
                });
    }

    public JdbcQuerySource(DatabaseConnectionInfo info, String sql, List<Object> params, int fetchSize) {
        this(openValidated(info, sql, fetchSize), sql, params, fetchSize, LargeFieldPolicy.defaults());
    }

    public JdbcQuerySource(DatabaseConnectionInfo info, String sql, List<Object> params, int fetchSize, JdbcDriverLoader loader) {
        this(openValidated(info, sql, fetchSize, loader), sql, params, fetchSize, LargeFieldPolicy.defaults());
    }

    public JdbcQuerySource(DatabaseConnectionInfo info, String sql, List<Object> params, int fetchSize, LargeFieldPolicy policy) {
        this(openValidated(info, sql, fetchSize), sql, params, fetchSize, policy);
    }

    public JdbcQuerySource(DatabaseConnectionInfo info, String sql, List<Object> params, int fetchSize,
                           JdbcDriverLoader loader, LargeFieldPolicy policy) {
        this(openValidated(info, sql, fetchSize, loader), sql, params, fetchSize, policy);
    }

    public JdbcQuerySource(ConnectionProvider p, String sql, List<Object> params, int fetchSize) {
        this(p, sql, params, fetchSize, LargeFieldPolicy.defaults());
    }

    public JdbcQuerySource(ConnectionProvider p, String sql, List<Object> params, int fetchSize, LargeFieldPolicy policy) {
        validateQuery(sql, fetchSize);
        if (p == null) throw new IllegalArgumentException("connection provider is required");
        provider = p;
        this.sql = sql;
        this.params = params == null ? Collections.<Object>emptyList() : new ArrayList<Object>(params);
        this.fetchSize = fetchSize;
        this.largeFieldPolicy = policy == null ? LargeFieldPolicy.defaults() : policy;
        this.execution = new JdbcExecutionAdapter(p);
    }

    private static ConnectionProvider openValidated(DatabaseConnectionInfo info, String sql, int fetchSize) {
        validateQuery(sql, fetchSize);
        return JdbcConnectionFactory.open(info);
    }

    private static ConnectionProvider openValidated(DatabaseConnectionInfo info, String sql, int fetchSize,
                                                      JdbcDriverLoader loader) {
        validateQuery(sql, fetchSize);
        return JdbcConnectionFactory.open(info, loader);
    }

    private static void validateQuery(String sql, int fetchSize) {
        if (sql == null || !sql.trim().toLowerCase(Locale.ENGLISH).startsWith("select"))
            throw new IllegalArgumentException("only SELECT is allowed");
        if (fetchSize <= 0) throw new IllegalArgumentException("fetchSize must be positive");
    }

    private RecordSchema recordSchema() {
        if (schema == null) schema = execution.query("inspect query", sql, params, fetchSize, null,
                new JdbcExecutionAdapter.ResultSetHandler<RecordSchema>() {
                    public RecordSchema extractData(ResultSet resultSet) throws SQLException {
                        return readSchema(resultSet.getMetaData());
                    }
                });
        return schema;
    }

    public SchemaDescriptor schema() {
        RecordSchema result = recordSchema();
        log.debug("JDBC 查询 Schema 已解析 runId={} fields={}", LoggingSupport.currentRunId(), result.getFields().size());
        return SchemaDescriptor.of(result);
    }

    private RecordSchema readSchema(ResultSetMetaData m) throws SQLException {
        List<FieldSchema> fs = new ArrayList<FieldSchema>();
        for (int i = 1; i <= m.getColumnCount(); i++) {
            // 策略可能主动返回 null，因此即使数据库声明 NOT NULL，也必须对外声明可空。
            boolean nullable = m.isNullable(i) != ResultSetMetaData.columnNoNulls
                    || largeFieldPolicy.canReturnNull(m.getColumnLabel(i), m.getColumnType(i), m.getColumnTypeName(i));
            fs.add(new FieldSchema(m.getColumnLabel(i), JdbcTypes.logical(m.getColumnType(i)), nullable,
                    m.getColumnDisplaySize(i), m.getPrecision(i), m.getScale(i)));
        }
        return new RecordSchema(fs);
    }

    /** 不读取二进制字段，避免 BLOB/BINARY 被 JDBC 驱动物化到应用内存。 */
    private DataRecord readRecord(ResultSet r, ResultSetMetaData metadata, RecordSchema sc, boolean skipBinary,
                                  boolean collectStatistics) throws SQLException {
        List<Object> v = new ArrayList<Object>();
        for (int i = 0; i < sc.getFields().size(); i++) {
            FieldSchema field = sc.getFields().get(i);
            String name = metadata.getColumnLabel(i + 1);
            // 预览模式沿用历史行为：二进制字段直接置空，但不计入任务统计。
            if (skipBinary && field.getLogicalType() == LogicalType.BINARY) {
                v.add(null);
            } else if (largeFieldPolicy.action(name, metadata.getColumnType(i + 1), metadata.getColumnTypeName(i + 1))
                    == LargeFieldPolicy.Action.SKIP) {
                // SKIP 的关键是完全不调用 JdbcValueCodec，避免驱动先把 LOB 物化到内存。
                if (collectStatistics)
                    recordSkippedField(name);
                v.add(null);
            } else {
                // COPY 路径才读取实际值；阈值检查在读取后或 LOB 长度检查时完成。
                Object value = readValue(r, i + 1, field, metadata.getColumnType(i + 1));
                if (value == ThresholdExceeded.INSTANCE || exceedsThreshold(value)) {
                    // 超限值按当前策略置空，并记录受影响字段和行数。
                    if (collectStatistics) recordSkippedField(name);
                    v.add(null);
                } else v.add(value);
            }
        }
        return new DataRecord(sc, v);
    }

    public void read(BatchConsumer c) {
        RecordSchema sc = recordSchema();
        resetStatistics();
        log.debug("JDBC 查询读取开始 runId={} fetchSize={} parameterCount={}", LoggingSupport.currentRunId(), fetchSize, params.size());
        execution.query("read query", sql, params, fetchSize, null, new JdbcExecutionAdapter.ResultSetHandler<Void>() {
            public Void extractData(ResultSet resultSet) throws SQLException {
                ResultSetMetaData metadata = resultSet.getMetaData();
                List<DataRecord> batch = new ArrayList<DataRecord>();
                while (resultSet.next()) {
                    batch.add(readRecord(resultSet, metadata, sc, false, true));
                    incrementReadRows();
                    if (batch.size() == fetchSize) {
                        c.accept(new RecordBatch(sc, batch));
                        batch = new ArrayList<DataRecord>();
                    }
                }
                if (!batch.isEmpty()) c.accept(new RecordBatch(sc, batch));
                return null;
            }
        });
        ReadStatistics result = getReadStatistics();
        log.debug("JDBC 查询读取完成 runId={} rows={} skippedFields={}", LoggingSupport.currentRunId(), result.getReadRows(), result.getSkippedFieldRows().size());
    }

    public void close() {
        provider.close();
        log.debug("JDBC 查询源已关闭 runId={}", LoggingSupport.currentRunId());
    }

    public synchronized ReadStatistics getReadStatistics() {
        // 返回不可变快照，避免调用方看到后续 read() 的中间状态。
        return new ReadStatistics(readRows, 0, skippedFieldRows);
    }

    private boolean exceedsThreshold(Object value) {
        if (value instanceof byte[] && largeFieldPolicy.maxBinaryBytes() != null)
            return ((byte[]) value).length > largeFieldPolicy.maxBinaryBytes();
        return value instanceof String && largeFieldPolicy.maxTextChars() != null
                && ((String) value).length() > largeFieldPolicy.maxTextChars();
    }

    /** 对 JDBC LOB 先检查驱动返回的长度，避免超限时物化实际内容。 */
    private Object readValue(ResultSet resultSet, int index, FieldSchema field, int jdbcType) throws SQLException {
        if (jdbcType == Types.BLOB && largeFieldPolicy.maxBinaryBytes() != null) {
            // 先取得 LOB 句柄并检查长度，超限时不读取具体字节。
            Blob blob = resultSet.getBlob(index);
            if (blob == null) return null;
            try {
                long length = blob.length();
                return length > largeFieldPolicy.maxBinaryBytes() || length > Integer.MAX_VALUE ? ThresholdExceeded.INSTANCE
                        : blob.getBytes(1, (int) length);
            } finally {
                blob.free();
            }
        }
        if (jdbcType == Types.CLOB && largeFieldPolicy.maxTextChars() != null) {
            // CLOB 同理：先检查字符数，再决定是否转成 String。
            Clob clob = resultSet.getClob(index);
            if (clob == null) return null;
            try {
                long length = clob.length();
                return length > largeFieldPolicy.maxTextChars() || length > Integer.MAX_VALUE ? ThresholdExceeded.INSTANCE
                        : clob.getSubString(1, (int) length);
            } finally {
                clob.free();
            }
        }
        return JdbcValueCodec.read(resultSet, index, field);
    }

    private synchronized void resetStatistics() {
        // 一个 Source 实例可能被重复执行，每次正式读取都从零开始统计。
        readRows = 0;
        skippedFieldRows.clear();
    }

    private synchronized void incrementReadRows() { readRows++; }

    private synchronized void recordSkippedField(String name) {
        Long count = skippedFieldRows.get(name);
        // 使用字段名聚合，便于结果层直接生成字段跳过报表。
        skippedFieldRows.put(name, count == null ? 1L : count + 1L);
    }

    private enum ThresholdExceeded { INSTANCE }
}
