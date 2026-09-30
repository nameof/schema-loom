package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.codec.TextValueCodec;
import io.github.nameof.schemaloom.internal.LoggingSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * CSV 文件 Source。
 * <p>未提供显式 Schema 时，{@link FileSchemaMode#ALL_STRING} 为默认模式，所有字段按字符串读取，
 * 此时 {@link InvalidValuePolicy} 不会触发；选择 {@link FileSchemaMode#INFER} 时，类型由最多 1000 行样本推断。
 * 提供显式 Schema 时以显式 Schema 为准，读取阶段的字段转换失败按非法值策略处理：</p>
 * <ul>
 *   <li>{@link InvalidValuePolicy#FAIL_FAST}：首个失败立即终止读取；</li>
 *   <li>{@link InvalidValuePolicy#SKIP_VALUE}：失败字段置为 null，保留整行，并按字段计入 skippedFieldRows；</li>
 *   <li>{@link InvalidValuePolicy#SKIP_ROW}：丢弃包含失败字段的整行，并计入 skippedRows。</li>
 * </ul>
 * 缺失列和空值按 null 处理，不计入非法值统计。文件无法读取、标题为空或重复、CSV 引号未闭合等结构错误始终终止读取。
 */
public final class CsvSource implements Source, ReadStatisticsProvider {
    private static final Logger log = LoggerFactory.getLogger(CsvSource.class);
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private final Path path;
    private final Charset charset;
    private final char delimiter;
    private final int headerLine;
    private final int batchSize;
    private final RecordSchema explicit;
    private final FileSchemaMode schemaMode;
    private final InvalidValuePolicy invalidValuePolicy;
    private RecordSchema inferred;
    private volatile ReadStatistics statistics = ReadStatistics.empty();

    public CsvSource(Path path) {
        this(path, null, StandardCharsets.UTF_8, ',', 0, DEFAULT_BATCH_SIZE, FileSchemaMode.ALL_STRING, InvalidValuePolicy.SKIP_VALUE);
    }

    public CsvSource(Path path, RecordSchema schema, Charset charset, char delimiter, int headerLine) {
        this(path, schema, charset, delimiter, headerLine, DEFAULT_BATCH_SIZE, schema == null ? FileSchemaMode.ALL_STRING : FileSchemaMode.INFER, InvalidValuePolicy.SKIP_VALUE);
    }

    public CsvSource(Path path, RecordSchema schema, Charset charset, char delimiter, int headerLine, int batchSize) {
        this(path, schema, charset, delimiter, headerLine, batchSize, schema == null ? FileSchemaMode.ALL_STRING : FileSchemaMode.INFER, InvalidValuePolicy.SKIP_VALUE);
    }

    public CsvSource(Path path, RecordSchema schema, Charset charset, char delimiter, int headerLine, int batchSize,
                     FileSchemaMode schemaMode, InvalidValuePolicy invalidValuePolicy) {
        this.path = Objects.requireNonNull(path, "path");
        this.explicit = schema;
        this.schemaMode = Objects.requireNonNull(schemaMode, "schemaMode");
        this.invalidValuePolicy = Objects.requireNonNull(invalidValuePolicy, "invalidValuePolicy");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.delimiter = delimiter;
        this.headerLine = headerLine;
        if (headerLine < 0) throw new IllegalArgumentException("headerLine");
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        this.batchSize = batchSize;
    }

    /** 返回显式 Schema；未提供时只扫描一次标题和最多 1000 行样本。 */
    private RecordSchema recordSchema() {
        if (explicit != null) return explicit;
        if (schemaMode == FileSchemaMode.ALL_STRING) return stringSchema();
        if (inferred == null) inferred = infer();
        return inferred;
    }

    public SchemaDescriptor schema() {
        RecordSchema result = recordSchema();
        log.debug("CSV Schema 已解析 runId={} file={} fields={}",
                LoggingSupport.currentRunId(), path.getFileName(), result.getFields().size());
        return SchemaDescriptor.of(result);
    }

    private RecordSchema stringSchema() {
        try (BufferedReader r = Files.newBufferedReader(path, charset)) {
            for (int i = 0; i < headerLine; i++) if (readRow(r) == null) throw new SchemaLoomException("CSV has no header");
            List<String> h = readRow(r);
            if (h == null || h.isEmpty()) throw new SchemaLoomException("CSV has no header");
            List<FieldSchema> fields = new ArrayList<FieldSchema>();
            Set<String> names = new HashSet<String>();
            for (String n : h) {
                if (n.trim().isEmpty() || !names.add(n)) throw new SchemaLoomException("empty or duplicate CSV header: " + n);
                fields.add(FieldSchema.of(n, LogicalType.STRING));
            }
            return new RecordSchema(fields);
        } catch (IOException e) { throw new SchemaLoomException("cannot read CSV", e); }
    }

    /** 根据标题和样本值推断字段类型，空值不参与推断。 */
    private RecordSchema infer() {
        try {
            BufferedReader r = Files.newBufferedReader(path, charset);
            try {
                // 先读取标题，再从后续数据行中收集推断样本。
                List<List<String>> sample = new ArrayList<List<String>>();
                // 推断字段和正式读取数据保持相同顺序：先跳过前置行，再读取标题。
                for (int i = 0; i < headerLine; i++) readRow(r);
                List<String> h = readRow(r);
                if (h == null || h.isEmpty()) throw new SchemaLoomException("CSV has no header");
                for (int i = 0; i < 1000; i++) {
                    List<String> row = readRow(r);
                    if (row == null) break;
                    sample.add(row);
                }
                List<FieldSchema> fs = new ArrayList<FieldSchema>();
                Set<String> names = new HashSet<String>();
                for (int c = 0; c < h.size(); c++) {
                    String n = h.get(c);
                    if (n.trim().isEmpty() || !names.add(n))
                        throw new SchemaLoomException("empty or duplicate CSV header: " + n);
                    LogicalType t = null;
                    Integer len = null;
                    for (List<String> row : sample) {
                        if (c >= row.size() || row.get(c).isEmpty()) continue;
                        String v = row.get(c);
                        LogicalType next = guess(v);
                        // 带前导零的数字通常是编码或编号，必须保留为字符串。
                        if (looksNumeric(v) && v.length() > 1 && v.charAt(0) == '0')
                            next = LogicalType.STRING;
                        t = t == null ? next : merge(t, next);
                        len = len == null ? v.length() : Math.max(len, v.length());
                    }
                    fs.add(new FieldSchema(n, t == null ? LogicalType.STRING : t, true, len, null, null));
                }
                return new RecordSchema(fs);
            } finally {
                r.close();
            }
        } catch (IOException e) {
            log.warn("CSV Schema 推断失败 runId={} file={} message={}",
                    LoggingSupport.currentRunId(), path.getFileName(), LoggingSupport.message(e));
            throw new SchemaLoomException("cannot read CSV", e);
        }
    }

    /** 重新打开文件并按批次流式读取，避免把整个 CSV 加载到内存。 */
    public void read(BatchConsumer consumer) {
        RecordSchema s = recordSchema();
        log.info("CSV 读取开始 runId={} file={} batchSize={}", LoggingSupport.currentRunId(), path.getFileName(), batchSize);
        long readRows = 0, skippedRows = 0;
        Map<String, Long> skippedFields = new LinkedHashMap<String, Long>();
        try {
            BufferedReader r = Files.newBufferedReader(path, charset);
            try {
                for (int i = 0; i < headerLine; i++) if (readRow(r) == null) return;
                List<String> h = readRow(r);
                if (h == null) throw new SchemaLoomException("CSV has no header");
                List<DataRecord> batch = new ArrayList<DataRecord>();
                String line;
                while ((line = r.readLine()) != null) {
                    List<String> row = parse(line);
                    List<Object> values = new ArrayList<Object>();
                    boolean skip = false;
                    for (int i = 0; i < s.getFields().size(); i++) {
                        String text = i < row.size() ? row.get(i) : null;
                        if (text == null) { values.add(null); continue; }
                        if (schemaMode == FileSchemaMode.ALL_STRING && explicit == null) { values.add(text); continue; }
                        try { values.add(text.isEmpty() ? null : TextValueCodec.parse(s.getFields().get(i), text)); }
                        catch (RuntimeException e) {
                            if (invalidValuePolicy == InvalidValuePolicy.FAIL_FAST) throw new SchemaLoomException("CSV value decode failed", e);
                            if (invalidValuePolicy == InvalidValuePolicy.SKIP_ROW) { skip = true; break; }
                            values.add(null);
                            String name = s.getFields().get(i).getName();
                            skippedFields.put(name, skippedFields.containsKey(name) ? skippedFields.get(name) + 1 : 1L);
                        }
                    }
                    if (skip) { skippedRows++; continue; }
                    batch.add(new DataRecord(s, values));
                    readRows++;
                    // 达到批大小后立即交给任务引擎处理。
                    if (batch.size() == batchSize) {
                        consumer.accept(new RecordBatch(s, batch));
                        batch = new ArrayList<DataRecord>();
                    }
                }
                if (!batch.isEmpty()) consumer.accept(new RecordBatch(s, batch));
                statistics = new ReadStatistics(readRows, skippedRows, skippedFields);
                log.info("CSV 读取完成 runId={} file={} rows={} skippedRows={} skippedFields={}",
                        LoggingSupport.currentRunId(), path.getFileName(), readRows, skippedRows, skippedFields.size());
            } finally {
                r.close();
            }
        } catch (IOException e) {
            log.warn("CSV 读取失败 runId={} file={} message={}",
                    LoggingSupport.currentRunId(), path.getFileName(), LoggingSupport.message(e));
            throw new SchemaLoomException("cannot read CSV", e);
        }
    }

    private List<String> readRow(BufferedReader r) throws IOException {
        String line = r.readLine();
        return line == null ? null : parse(line);
    }

    /** 解析单行 CSV，支持 delimiter、双引号包裹和双引号转义。 */
    private List<String> parse(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder b = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    b.append('"');
                    i++;
                } else q = !q;
            } else if (c == delimiter && !q) {
                out.add(b.toString());
                b.setLength(0);
            } else b.append(c);
        }
        if (q) throw new SchemaLoomException("unterminated CSV quote");
        out.add(b.toString());
        return out;
    }

    private static boolean looksNumeric(String v) {
        return v.matches("[-+]?\\d+(\\.\\d+)?");
    }

    private static LogicalType guess(String v) {
        if (v.matches("true|false")) return LogicalType.BOOLEAN;
        if (v.matches("[-+]?\\d+")) return LogicalType.INT64;
        if (v.matches("[-+]?\\d+\\.\\d+")) return LogicalType.DECIMAL;
        try {
            LocalDate.parse(v);
            return LogicalType.DATE;
        } catch (Exception ignored) {
        }
        try {
            LocalDateTime.parse(v);
            return LogicalType.TIMESTAMP;
        } catch (Exception ignored) {
        }
        return LogicalType.STRING;
    }

    /** 合并同一列的样本类型，不兼容时退化为 STRING。 */
    private static LogicalType merge(LogicalType a, LogicalType b) {
        if (a == b) return a;
        if (a == LogicalType.STRING || b == LogicalType.STRING) return LogicalType.STRING;
        if ((a == LogicalType.INT64 && b == LogicalType.DECIMAL) || (a == LogicalType.DECIMAL && b == LogicalType.INT64))
            return LogicalType.DECIMAL;
        if ((a == LogicalType.DATE && b == LogicalType.TIMESTAMP) || (a == LogicalType.TIMESTAMP && b == LogicalType.DATE))
            return LogicalType.TIMESTAMP;
        return LogicalType.STRING;
    }

    public void close() {
    }

    public ReadStatistics getReadStatistics() { return statistics; }
}
