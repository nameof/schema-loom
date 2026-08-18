package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.codec.TextValueCodec;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class CsvSource implements Source {
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private final Path path;
    private final Charset charset;
    private final char delimiter;
    private final int headerLine;
    private final int batchSize;
    private final RecordSchema explicit;
    private RecordSchema inferred;

    public CsvSource(Path path) {
        this(path, null, StandardCharsets.UTF_8, ',', 0, DEFAULT_BATCH_SIZE);
    }

    public CsvSource(Path path, RecordSchema schema, Charset charset, char delimiter, int headerLine) {
        this(path, schema, charset, delimiter, headerLine, DEFAULT_BATCH_SIZE);
    }

    public CsvSource(Path path, RecordSchema schema, Charset charset, char delimiter, int headerLine, int batchSize) {
        this.path = Objects.requireNonNull(path, "path");
        this.explicit = schema;
        this.charset = Objects.requireNonNull(charset, "charset");
        this.delimiter = delimiter;
        this.headerLine = headerLine;
        if (headerLine < 0) throw new IllegalArgumentException("headerLine");
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        this.batchSize = batchSize;
    }

    /** 返回显式 Schema；未提供时只扫描一次标题和最多 1000 行样本。 */
    public RecordSchema schema() {
        if (explicit != null) return explicit;
        if (inferred == null) inferred = infer();
        return inferred;
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
            throw new SchemaLoomException("cannot read CSV", e);
        }
    }

    /** 重新打开文件并按批次流式读取，避免把整个 CSV 加载到内存。 */
    public void read(BatchConsumer consumer) {
        RecordSchema s = schema();
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
                    for (int i = 0; i < s.getFields().size(); i++)
                        values.add(i < row.size() ? TextValueCodec.parse(s.getFields().get(i), row.get(i)) : null);
                    batch.add(new DataRecord(s, values));
                    // 达到批大小后立即交给任务引擎处理。
                    if (batch.size() == batchSize) {
                        consumer.accept(new RecordBatch(s, batch));
                        batch = new ArrayList<DataRecord>();
                    }
                }
                if (!batch.isEmpty()) consumer.accept(new RecordBatch(s, batch));
            } finally {
                r.close();
            }
        } catch (IOException e) {
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
}
