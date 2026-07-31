package io.github.nameof.schemaloom.source;

import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.sax.handler.RowHandler;
import io.github.nameof.schemaloom.api.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class XlsxSource implements Source {
    private final Path path;
    private final String sheet;
    private final RecordSchema explicit;
    private RecordSchema inferred;

    public XlsxSource(Path path, String sheet, RecordSchema explicit) {
        if (!path.toString().toLowerCase(Locale.ENGLISH).endsWith(".xlsx"))
            throw new IllegalArgumentException("only .xlsx is supported");
        this.path = path;
        this.sheet = sheet;
        this.explicit = explicit;
    }

    public RecordSchema schema() {
        if (explicit != null) return explicit;
        if (inferred == null) {
            final List<List<Object>> rows = new ArrayList<List<Object>>();
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

    public void read(final BatchConsumer c) {
        final RecordSchema s = schema();
        ExcelUtil.readBySax(path.toFile(), sheet, new Handler() {
            private boolean head = true;
            private List<DataRecord> b = new ArrayList<DataRecord>();

            public void handle(int sh, long row, List<Object> values) {
                if (head) {
                    head = false;
                    return;
                }
                List<Object> v = new ArrayList<Object>();
                for (int i = 0; i < s.getFields().size(); i++) v.add(i < values.size() ? values.get(i) : null);
                b.add(new DataRecord(s, v));
                if (b.size() == 1000) {
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
                        if (v instanceof Boolean) t = merge(t, LogicalType.BOOLEAN);
                        else if (v instanceof Number) t = merge(t, LogicalType.DECIMAL);
                        else if (v instanceof Date) t = merge(t, LogicalType.TIMESTAMP);
                    }
                fs.add(FieldSchema.of(n, t));
            }
            return new RecordSchema(fs);
        }

        private static LogicalType merge(LogicalType a, LogicalType b) {
            return a == LogicalType.STRING ? b : a == b ? a : LogicalType.STRING;
        }
    }
}
