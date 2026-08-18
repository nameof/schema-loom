package io.github.nameof.schemaloom.target;

import cn.hutool.poi.excel.*;
import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.codec.ExcelValueCodec;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class XlsxTarget implements Target {
    private final Path path;
    private BigExcelWriter writer;
    private RecordSchema schema;
    private Path part;
    private int rows;
    private final int maxRowsPerSheet;

    public XlsxTarget(Path path) {
        this(path, 1048576);
    }

    XlsxTarget(Path path, int maxRowsPerSheet) {
        if (!path.toString().toLowerCase(Locale.ENGLISH).endsWith(".xlsx"))
            throw new IllegalArgumentException("only .xlsx is supported");
        if (maxRowsPerSheet < 2) throw new IllegalArgumentException("maxRowsPerSheet must include a header and data row");
        this.path = path;
        this.maxRowsPerSheet = maxRowsPerSheet;
    }

    /** 仅支持 REPLACE，并先创建 .part 工作簿避免覆盖已有文件。 */
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

    /** 流式写入一个批次，达到单 Sheet 行数上限后创建下一个 Sheet。 */
    public BatchWriteResult write(RecordBatch b) {
        try {
            for (DataRecord r : b.getRecords()) {
                List<Object> values = new ArrayList<Object>();
                for (int i = 0; i < schema.getFields().size(); i++)
                    values.add(ExcelValueCodec.encode(schema.getFields().get(i), r.get(i)));
                writer.writeRow(values);
                rows++;
                // rows 包含标题行，因此切换 Sheet 时要重新写入标题。
                if (rows >= maxRowsPerSheet) {
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

    /** 关闭工作簿并将 .part 替换为最终 XLSX 文件。 */
    public void close() {
        if (writer == null) return;
        BigExcelWriter current = writer;
        try {
            current.close();
            writer = null;
            Files.move(part, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            writer = null;
            throw new SchemaLoomException("cannot close XLSX", e);
        }
    }
}
