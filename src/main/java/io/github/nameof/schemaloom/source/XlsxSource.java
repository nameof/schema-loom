package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.codec.ExcelValueCodec;
import io.github.nameof.schemaloom.internal.LoggingSupport;
import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 同时读取 XLS/XLSX 的文件 Source，默认使用 {@link FileSchemaMode#ALL_STRING}。
 * <p>未提供显式 Schema 时，ALL_STRING 将所有单元格格式化为字符串，非法值策略不触发；
 * 选择 {@link FileSchemaMode#INFER} 时，基于最多 1000 行样本推断类型。提供显式 Schema 时以显式 Schema 为准。</p>
 *
 * <p>字段解码失败按 {@link InvalidValuePolicy} 处理：
 * FAIL_FAST 立即终止；
 * SKIP_VALUE 将字段置为 null、保留整行并按字段计入 skippedFieldRows；
 * SKIP_ROW 丢弃整行并计入 skippedRows。空单元格按 null 处理，不计入非法值统计。
 * 工作簿损坏、Sheet 不存在、标题异常等结构错误始终终止读取。</p>
 *
 * <p>为统一支持两种格式，当前实现使用 POI WorkbookFactory，读取时会将工作簿加载到内存。</p>
 */
public final class XlsxSource implements Source, ReadStatisticsProvider {
    private static final Logger log = LoggerFactory.getLogger(XlsxSource.class);
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private final Path path;
    private final String sheet;
    private final int batchSize;
    private final RecordSchema explicit;
    private final FileSchemaMode schemaMode;
    private final InvalidValuePolicy invalidValuePolicy;
    private RecordSchema inferred;
    private volatile ReadStatistics statistics = ReadStatistics.empty();

    public XlsxSource(Path path, String sheet, RecordSchema explicit) {
        this(path, sheet, explicit, DEFAULT_BATCH_SIZE, explicit == null ? FileSchemaMode.ALL_STRING : FileSchemaMode.INFER, InvalidValuePolicy.SKIP_VALUE);
    }

    public XlsxSource(Path path, String sheet, RecordSchema explicit, int batchSize) {
        this(path, sheet, explicit, batchSize, explicit == null ? FileSchemaMode.ALL_STRING : FileSchemaMode.INFER, InvalidValuePolicy.SKIP_VALUE);
    }

    public XlsxSource(Path path, String sheet, RecordSchema explicit, int batchSize, FileSchemaMode mode, InvalidValuePolicy policy) {
        String n = Objects.requireNonNull(path, "path").toString().toLowerCase(Locale.ENGLISH);
        if (!n.endsWith(".xlsx") && !n.endsWith(".xls"))
            throw new IllegalArgumentException("only .xls or .xlsx is supported");
        if (batchSize <= 0)
            throw new IllegalArgumentException("batchSize must be positive");
        this.path = path;
        this.sheet = sheet;
        this.explicit = explicit;
        this.batchSize = batchSize;
        this.schemaMode = Objects.requireNonNull(mode, "schemaMode");
        this.invalidValuePolicy = Objects.requireNonNull(policy, "invalidValuePolicy");
    }

    private RecordSchema recordSchema() {
        if (explicit != null) return explicit;
        if (inferred != null) return inferred;
        List<List<Object>> rows = readRows(1001);
        if (rows.isEmpty()) throw new SchemaLoomException("XLSX has no rows");
        List<Object> h = rows.get(0);
        List<FieldSchema> fs = new ArrayList<FieldSchema>();
        Set<String> names = new HashSet<String>();
        for (int i = 0; i < h.size(); i++) {
            String name = String.valueOf(h.get(i));
            if (name.trim().isEmpty() || !names.add(name))
                throw new SchemaLoomException("empty or duplicate XLSX header: " + name);
            LogicalType type = schemaMode == FileSchemaMode.ALL_STRING ? LogicalType.STRING : null;
            for (int r = 1; type != LogicalType.STRING && r < rows.size(); r++)
                if (i < rows.get(r).size() && rows.get(r).get(i) != null) {
                    LogicalType next = valueType(rows.get(r).get(i));
                    type = type == null ? next : merge(type, next);
                }
            fs.add(FieldSchema.of(name, type == null ? LogicalType.STRING : type));
        }
        return inferred = new RecordSchema(fs);
    }

    public SchemaDescriptor schema() {
        RecordSchema result = recordSchema();
        log.debug("XLSX Schema 已解析 runId={} file={} fields={}",
                LoggingSupport.currentRunId(), path.getFileName(), result.getFields().size());
        return SchemaDescriptor.of(result);
    }

    public void read(BatchConsumer consumer) {
        RecordSchema s = recordSchema();
        log.info("XLSX 读取开始 runId={} file={} batchSize={}", LoggingSupport.currentRunId(), path.getFileName(), batchSize);
        long read = 0, skipped = 0;
        Map<String, Long> bad = new LinkedHashMap<String, Long>();
        try (InputStream in = Files.newInputStream(path); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = sheet == null ? wb.getSheetAt(0) : wb.getSheet(sheet);
            if (sh == null) throw new SchemaLoomException("XLSX sheet not found: " + sheet);
            DataFormatter fmt = new DataFormatter();
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            List<DataRecord> batch = new ArrayList<DataRecord>();
            boolean header = true;
            for (Row row : sh) {
                if (header) {
                    header = false;
                    continue;
                }
                List<Object> vals = new ArrayList<Object>();
                boolean skip = false;
                for (int i = 0; i < s.getFields().size(); i++) {
                    Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (schemaMode == FileSchemaMode.ALL_STRING && explicit == null) {
                        vals.add(cell == null ? null : fmt.formatCellValue(cell, eval));
                        continue;
                    }
                    Object raw = cell == null ? null : raw(cell, eval);
                    try {
                        vals.add(raw == null ? null : ExcelValueCodec.decode(s.getFields().get(i), raw));
                    } catch (RuntimeException e) {
                        if (invalidValuePolicy == InvalidValuePolicy.FAIL_FAST)
                            throw new SchemaLoomException("XLSX value decode failed at row " + row.getRowNum(), e);
                        if (invalidValuePolicy == InvalidValuePolicy.SKIP_ROW) {
                            skip = true;
                            break;
                        }
                        vals.add(null);
                        String k = s.getFields().get(i).getName();
                        bad.put(k, bad.containsKey(k) ? bad.get(k) + 1 : 1L);
                    }
                }
                if (skip) {
                    skipped++;
                    continue;
                }
                batch.add(new DataRecord(s, vals));
                read++;
                if (batch.size() == batchSize) {
                    consumer.accept(new RecordBatch(s, batch));
                    batch = new ArrayList<DataRecord>();
                }
            }
            if (!batch.isEmpty()) consumer.accept(new RecordBatch(s, batch));
            statistics = new ReadStatistics(read, skipped, bad);
            log.info("XLSX 读取完成 runId={} file={} rows={} skippedRows={} skippedFields={}",
                    LoggingSupport.currentRunId(), path.getFileName(), read, skipped, bad.size());
        } catch (IOException e) {
            log.warn("XLSX 读取失败 runId={} file={} message={}",
                    LoggingSupport.currentRunId(), path.getFileName(), LoggingSupport.message(e));
            throw new SchemaLoomException("cannot read XLS/XLSX", e);
        }
    }

    private List<List<Object>> readRows(int limit) {
        List<List<Object>> rows = new ArrayList<List<Object>>();
        try (InputStream in = Files.newInputStream(path); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = sheet == null ? wb.getSheetAt(0) : wb.getSheet(sheet);
            if (sh == null) throw new SchemaLoomException("XLSX sheet not found: " + sheet);
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            for (Row row : sh) {
                if (rows.size() >= limit) break;
                List<Object> v = new ArrayList<Object>();
                for (Cell c : row) v.add(raw(c, eval));
                rows.add(v);
            }
            return rows;
        } catch (IOException e) {
            throw new SchemaLoomException("cannot read XLS/XLSX", e);
        }
    }

    private static Object raw(Cell c, FormulaEvaluator e) {
        return c.getCellType() == CellType.FORMULA ? rawValue(c, e.evaluate(c)) : rawValue(c, null);
    }

    private static Object rawValue(Cell c, CellValue v) {
        CellType t = v == null ? c.getCellType() : v.getCellType();
        if (t == CellType.BLANK) return null;
        if (t == CellType.STRING) return v == null ? c.getStringCellValue() : v.getStringValue();
        if (t == CellType.BOOLEAN) return v == null ? c.getBooleanCellValue() : v.getBooleanValue();
        if (t == CellType.NUMERIC)
            return v == null ? (DateUtil.isCellDateFormatted(c) ? c.getDateCellValue() : c.getNumericCellValue()) : v.getNumberValue();
        return null;
    }

    private static LogicalType valueType(Object v) {
        if (v instanceof Boolean) return LogicalType.BOOLEAN;
        if (v instanceof Number) return LogicalType.DECIMAL;
        if (v instanceof Date) return LogicalType.TIMESTAMP;
        return LogicalType.STRING;
    }

    private static LogicalType merge(LogicalType a, LogicalType b) {
        return a == b ? a : LogicalType.STRING;
    }

    public void close() {
    }

    public ReadStatistics getReadStatistics() {
        return statistics;
    }
}
