package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.codec.JdbcValueCodec;
import io.github.nameof.schemaloom.driver.*;

import java.sql.*;
import java.time.*;
import java.util.*;

public final class JdbcQuerySource implements Source, ReadStatisticsProvider {
    private final ConnectionProvider provider;
    private final String sql;
    private final List<Object> params;
    private final int fetchSize;
    private final LargeFieldPolicy largeFieldPolicy;
    // key 为结果集字段名，value 为该字段被置空的行数。
    private final Map<String, Long> skippedFieldRows = new LinkedHashMap<>();
    // 只统计正式 read()，preview() 不污染任务统计。
    private long readRows;
    private RecordSchema schema;

    /** 受限读取结果集，供表预览复用查询源的类型映射逻辑。 */
    List<DataRecord> readRows(int maxRows) {
        RecordSchema sc = recordSchema();
        try {
            PreparedStatement s = provider.getConnection().prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            try {
                s.setFetchSize(fetchSize);
                s.setMaxRows(maxRows);
                bind(s);
                ResultSet r = s.executeQuery();
                try {
                    List<DataRecord> records = new ArrayList<DataRecord>();
                    while (r.next()) {
                        records.add(readRecord(r, r.getMetaData(), sc, true, false));
                    }
                    return records;
                } finally {
                    r.close();
                }
            } finally {
                s.close();
            }
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot read limited query", e);
        }
    }

    public JdbcQuerySource(DatabaseConnectionInfo info, String sql, List<Object> params, int fetchSize) {
        this(JdbcConnectionFactory.open(info), sql, params, fetchSize, LargeFieldPolicy.defaults());
    }

    public JdbcQuerySource(DatabaseConnectionInfo info, String sql, List<Object> params, int fetchSize, JdbcDriverLoader loader) {
        this(JdbcConnectionFactory.open(info, loader), sql, params, fetchSize, LargeFieldPolicy.defaults());
    }

    public JdbcQuerySource(DatabaseConnectionInfo info, String sql, List<Object> params, int fetchSize, LargeFieldPolicy policy) {
        this(JdbcConnectionFactory.open(info), sql, params, fetchSize, policy);
    }

    public JdbcQuerySource(DatabaseConnectionInfo info, String sql, List<Object> params, int fetchSize,
                           JdbcDriverLoader loader, LargeFieldPolicy policy) {
        this(JdbcConnectionFactory.open(info, loader), sql, params, fetchSize, policy);
    }

    public JdbcQuerySource(ConnectionProvider p, String sql, List<Object> params, int fetchSize) {
        this(p, sql, params, fetchSize, LargeFieldPolicy.defaults());
    }

    public JdbcQuerySource(ConnectionProvider p, String sql, List<Object> params, int fetchSize, LargeFieldPolicy policy) {
        if (sql == null || !sql.trim().toLowerCase(Locale.ENGLISH).startsWith("select"))
            throw new IllegalArgumentException("only SELECT is allowed");
        if (p == null) throw new IllegalArgumentException("connection provider is required");
        if (fetchSize <= 0) throw new IllegalArgumentException("fetchSize must be positive");
        provider = p;
        this.sql = sql;
        this.params = params == null ? Collections.<Object>emptyList() : new ArrayList<Object>(params);
        this.fetchSize = fetchSize;
        this.largeFieldPolicy = policy == null ? LargeFieldPolicy.defaults() : policy;
    }

    private RecordSchema recordSchema() {
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

    public SchemaDescriptor schema() { return SchemaDescriptor.of(recordSchema()); }

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

    private void bind(PreparedStatement s) throws SQLException {
        for (int i = 0; i < params.size(); i++) s.setObject(i + 1, params.get(i));
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
        try {
            PreparedStatement s = provider.getConnection().prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            try {
                s.setFetchSize(fetchSize);
                bind(s);
                ResultSet r = s.executeQuery();
                try {
                    List<DataRecord> b = new ArrayList<DataRecord>();
                    while (r.next()) {
                        // 每一行先按字段策略读取，再放入批次交给 EtlTask。
                        b.add(readRecord(r, r.getMetaData(), sc, false, true));
                        incrementReadRows();
                        if (b.size() == fetchSize) {
                            // 达到批量大小立即回调，避免把整个结果集物化。
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
