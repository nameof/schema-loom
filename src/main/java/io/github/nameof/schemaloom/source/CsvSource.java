package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class CsvSource implements Source {
    private final Path path;
    private final Charset charset;
    private final char delimiter;
    private final int headerLine;
    private final RecordSchema explicit;
    private RecordSchema inferred;

    public CsvSource(Path path) {
        this(path, null, StandardCharsets.UTF_8, ',', 0);
    }

    public CsvSource(Path path, RecordSchema schema, Charset charset, char delimiter, int headerLine) {
        this.path = Objects.requireNonNull(path, "path");
        this.explicit = schema;
        this.charset = Objects.requireNonNull(charset, "charset");
        this.delimiter = delimiter;
        this.headerLine = headerLine;
        if (headerLine < 0) throw new IllegalArgumentException("headerLine");
    }

    public RecordSchema schema() {
        if (explicit != null) return explicit;
        if (inferred == null) inferred = infer();
        return inferred;
    }

    private RecordSchema infer() {
        try {
            BufferedReader r = Files.newBufferedReader(path, charset);
            try {
                List<String> h = readRow(r);
                if (h == null || h.isEmpty()) throw new SchemaLoomException("CSV has no header");
                List<List<String>> sample = new ArrayList<List<String>>();
                for (int i = 0; i < headerLine; i++) readRow(r);
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
                    LogicalType t = LogicalType.STRING;
                    Integer len = null;
                    for (List<String> row : sample) {
                        if (c >= row.size() || row.get(c).isEmpty()) continue;
                        String v = row.get(c);
                        LogicalType next = guess(v);
                        if (t == LogicalType.STRING && looksNumeric(v) && v.length() > 0 && v.charAt(0) == '0' && v.length() > 1)
                            next = LogicalType.STRING;
                        t = merge(t, next);
                        len = Math.max(len, v.length());
                    }
                    fs.add(new FieldSchema(n, t, true, len, null, null));
                }
                return new RecordSchema(fs);
            } finally {
                r.close();
            }
        } catch (IOException e) {
            throw new SchemaLoomException("cannot read CSV", e);
        }
    }

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
                        values.add(i < row.size() ? convert(row.get(i), s.getFields().get(i).getLogicalType()) : null);
                    batch.add(new DataRecord(s, values));
                    if (batch.size() == 1000) {
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

    private static LogicalType merge(LogicalType a, LogicalType b) {
        if (a == b) return a;
        if (a == LogicalType.STRING || b == LogicalType.STRING) return LogicalType.STRING;
        if ((a == LogicalType.INT64 && b == LogicalType.DECIMAL) || (a == LogicalType.DECIMAL && b == LogicalType.INT64))
            return LogicalType.DECIMAL;
        if ((a == LogicalType.DATE && b == LogicalType.TIMESTAMP) || (a == LogicalType.TIMESTAMP && b == LogicalType.DATE))
            return LogicalType.TIMESTAMP;
        return LogicalType.STRING;
    }

    private static Object convert(String v, LogicalType t) {
        if (v == null || v.isEmpty()) return null;
        try {
            switch (t) {
                case BOOLEAN:
                    return Boolean.valueOf(v);
                case INT16:
                    return Short.valueOf(v);
                case INT32:
                    return Integer.valueOf(v);
                case INT64:
                    return Long.valueOf(v);
                case DECIMAL:
                    return new java.math.BigDecimal(v);
                case DATE:
                    return LocalDate.parse(v);
                case TIMESTAMP:
                    return LocalDateTime.parse(v);
                default:
                    return v;
            }
        } catch (RuntimeException e) {
            throw new SchemaLoomException("invalid CSV value: " + v, e);
        }
    }

    public void close() {
    }
}
