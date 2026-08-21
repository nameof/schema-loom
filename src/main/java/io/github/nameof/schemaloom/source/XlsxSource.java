package io.github.nameof.schemaloom.source;

import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.sax.handler.RowHandler;
import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.codec.ExcelValueCodec;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class XlsxSource implements Source {
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private final Path path;
    private final String sheet;
    private final int batchSize;
    private final RecordSchema explicit;
    private final boolean skipMalformed;
    private RecordSchema inferred;

    public XlsxSource(Path path, String sheet, RecordSchema explicit) {
        this(path, sheet, explicit, DEFAULT_BATCH_SIZE, false);
    }

    public XlsxSource(Path path, String sheet, RecordSchema explicit, int batchSize) {
        this(path, sheet, explicit, batchSize, false);
    }

    public XlsxSource(Path path, String sheet, RecordSchema explicit, int batchSize, boolean skipMalformed) {
        if (!path.toString().toLowerCase(Locale.ENGLISH).endsWith(".xlsx"))
            throw new IllegalArgumentException("only .xlsx is supported");
        this.path = path;
        this.sheet = sheet;
        this.explicit = explicit;
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        this.batchSize = batchSize;
        this.skipMalformed = skipMalformed;
    }

    /** 返回显式 Schema；否则扫描指定 Sheet 的标题和最多 1000 行样本。 */
    private RecordSchema recordSchema() {
        if (explicit != null) return explicit;
        if (inferred == null) {
            final List<List<Object>> rows = new ArrayList<>();
            ExcelUtil.readBySax(path.toFile(), sheet, new Handler() {
                public void handle(int s, long r, List<Object> row) {
                    if (rows.size() < 1001) rows.add(row);
                }
            });
            if (rows.isEmpty()) throw new SchemaLoomException("XLSX has no rows");
            inferred = Infer.schema(rows);
        }
        return inferred;
    }

    public SchemaDescriptor schema() { return SchemaDescriptor.of(recordSchema()); }

    /** 使用 Hutool SAX 流式读取指定 Sheet，并按批次回调数据。
     *  当 skipMalformed 为 true 时，解码失败或列数严重不匹配的行会被静默跳过，
     *  适用于 Excel 中夹杂章节标题等非数据行的场景。 */
    public void read(final BatchConsumer c) {
        final RecordSchema s = recordSchema();
        final int fieldCount = s.getFields().size();
        ExcelUtil.readBySax(path.toFile(), sheet, new Handler() {
            private boolean head = true;
            private List<DataRecord> b = new ArrayList<DataRecord>();

            public void handle(int sh, long row, List<Object> values) {
                if (head) {
                    // 第一行始终是标题，不作为数据记录输出。
                    head = false;
                    return;
                }
                // 列数不足表头一半的行视为章节标题或空行，直接跳过。
                if (values.size() < fieldCount / 2) return;
                try {
                    List<Object> v = new ArrayList<Object>();
                    for (int i = 0; i < fieldCount; i++) {
                        v.add(i < values.size() ? ExcelValueCodec.decode(s.getFields().get(i), values.get(i)) : null);
                    }
                    b.add(new DataRecord(s, v));
                } catch (RuntimeException e) {
                    if (!skipMalformed) throw new SchemaLoomException("row " + row + " decode failed", e);
                    // skipMalformed 模式：跳过无法解码的行（如章节标题行）。
                }
                if (b.size() == batchSize) {
                    c.accept(new RecordBatch(s, b));
                    b = new ArrayList<DataRecord>();
                }
            }

            public void doAfterAllAnalysed() {
                if (!b.isEmpty()) c.accept(new RecordBatch(s, b));
            }
        });
    }

    public void close() {
    }

    private abstract static class Handler implements RowHandler {
        public void handle(int s, long r, List<Object> row) {
        }

        public void doAfterAllAnalysed() {
        }
    }

    /** XLSX 单元格类型推断工具，数字统一使用 DECIMAL 表示。 */
    static final class Infer {
        static RecordSchema schema(List<List<Object>> rows) {
            List<Object> h = rows.get(0);
            List<FieldSchema> fs = new ArrayList<FieldSchema>();
            Set<String> names = new HashSet<String>();
            for (int i = 0; i < h.size(); i++) {
                String n = String.valueOf(h.get(i));
                if (n.trim().isEmpty() || !names.add(n))
                    throw new SchemaLoomException("empty or duplicate XLSX header: " + n);
                LogicalType t = LogicalType.STRING;
                for (int r = 1; r < rows.size(); r++)
                    if (i < rows.get(r).size() && rows.get(r).get(i) != null) {
                        Object v = rows.get(r).get(i);
                        t = merge(t, valueType(v));
                    }
                fs.add(FieldSchema.of(n, t));
            }
            return new RecordSchema(fs);
        }

        /** 将 Hutool 返回的单元格值映射为 SchemaLoom 逻辑类型。 */
        private static LogicalType valueType(Object value) {
            if (value instanceof Boolean) return LogicalType.BOOLEAN;
            if (value instanceof Number) return LogicalType.DECIMAL;
            if (value instanceof Date) return LogicalType.TIMESTAMP;
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (text.matches("[-+]?\\d+(\\.\\d+)?") && !(text.length() > 1 && text.charAt(0) == '0'))
                    return LogicalType.DECIMAL;
            }
            return LogicalType.STRING;
        }

        /** 合并样本类型，不兼容时退化为 STRING。 */
        private static LogicalType merge(LogicalType a, LogicalType b) {
            return a == LogicalType.STRING ? b : a == b ? a : LogicalType.STRING;
        }
    }
}
