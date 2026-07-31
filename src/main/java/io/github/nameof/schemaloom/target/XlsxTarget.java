package io.github.nameof.schemaloom.target;

import cn.hutool.poi.excel.*;
import io.github.nameof.schemaloom.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class XlsxTarget implements Target {
    private final Path path;
    private BigExcelWriter writer;
    private RecordSchema schema;
    private Path part;
    private int rows;

    public XlsxTarget(Path path) {
        if (!path.toString().toLowerCase(Locale.ENGLISH).endsWith(".xlsx"))
            throw new IllegalArgumentException("only .xlsx is supported");
        this.path = path;
    }

    public void prepare(RecordSchema s, TargetMode mode) {
        if (mode != TargetMode.REPLACE) throw new IllegalArgumentException("XLSX supports REPLACE only");
        schema = s;
        part = path.resolveSibling(path.getFileName() + ".part");
        writer = ExcelUtil.getBigWriter(part.toFile(), "Sheet1");
        writer.writeHeadRow(names());
        rows = 1;
    }

    private List<String> names() {
        List<String> n = new ArrayList<String>();
        for (FieldSchema f : schema.getFields()) n.add(f.getName());
        return n;
    }

    public BatchWriteResult write(RecordBatch b) {
        try {
            for (DataRecord r : b.getRecords()) {
                writer.writeRow(r.getValues());
                rows++;
                if (rows >= 1048576) {
                    writer.setSheet("Sheet" + (writer.getWorkbook().getNumberOfSheets() + 1));
                    writer.writeHeadRow(names());
                    rows = 1;
                }
            }
            return new BatchWriteResult(b.size(), 0);
        } catch (RuntimeException e) {
            throw new SchemaLoomException("cannot write XLSX", e);
        }
    }

    public void close() {
        if (writer == null) return;
        try {
            writer.close();
            Files.move(part, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new SchemaLoomException("cannot close XLSX", e);
        }
    }
}
