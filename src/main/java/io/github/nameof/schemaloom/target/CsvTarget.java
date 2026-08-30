package io.github.nameof.schemaloom.target;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.codec.TextValueCodec;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

public final class CsvTarget implements Target {
    private final Path path;
    private final Charset charset;
    private final char delimiter;
    private Writer writer;
    private RecordSchema schema;
    private Path part;

    public CsvTarget(Path path) {
        this(path, StandardCharsets.UTF_8, ',');
    }

    public CsvTarget(Path path, Charset charset, char delimiter) {
        this.path = Objects.requireNonNull(path, "path");
        this.charset = charset;
        this.delimiter = delimiter;
    }

    /** 创建 CSV 输出；REPLACE 先写入 .part，APPEND 先校验已有标题。 */
    public List<EtlError> prepare(SchemaDescriptor descriptor, TargetMode mode) {
        this.schema = descriptor.getSchema();
        try {
            // REPLACE 不直接覆盖旧文件，避免任务失败时破坏原文件。
            Path out = mode == TargetMode.REPLACE ? path.resolveSibling(path.getFileName() + ".part") : path;
            part = mode == TargetMode.REPLACE ? out : null;
            if (mode == TargetMode.APPEND && Files.exists(out)) {
                // 追加前必须确认列顺序和标题完全一致。
                BufferedReader r = Files.newBufferedReader(out, charset);
                try {
                    String h = r.readLine();
                    if (h == null || !h.equals(joinHeader()))
                        throw new SchemaLoomException("CSV header differs from schema");
                } finally {
                    r.close();
                }
                writer = Files.newBufferedWriter(out, charset, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                writer = Files.newBufferedWriter(out, charset, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                writer.write(joinHeader());
                writer.write("\n");
            }
            return Collections.emptyList();
        } catch (IOException e) {
            throw new SchemaLoomException("cannot prepare CSV target", e);
        }
    }

    private String joinHeader() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < schema.getFields().size(); i++) {
            if (i > 0) b.append(delimiter);
            b.append(escape(schema.getFields().get(i).getName()));
        }
        return b.toString();
    }

    private String escape(String s) {
        return s.indexOf(delimiter) >= 0 || s.indexOf('"') >= 0 ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** 序列化并刷新一个批次；序列化失败时保留 .part 文件供排查。 */
    public BatchWriteResult write(RecordBatch batch) {
        try {
            for (DataRecord r : batch.getRecords()) {
                for (int i = 0; i < schema.getFields().size(); i++) {
                    if (i > 0) writer.write(delimiter);
                    Object v = r.get(i);
                    writer.write(escape(TextValueCodec.format(batch.getSchema().getFields().get(i), v)));
                }
                writer.write("\n");
            }
            writer.flush();
            return new BatchWriteResult(batch.size(), 0);
        } catch (IOException | RuntimeException e) {
            preservePartial();
            throw new SchemaLoomException("cannot write CSV", e);
        }
    }

    /** 正常关闭时将 .part 原子替换为目标文件；写入失败时 .part 已被保留。 */
    public void close() {
        if (writer == null) return;
        Writer current = writer;
        try {
            current.close();
            writer = null;
            if (part != null) {
                try {
                    Files.move(part, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(part, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            writer = null;
            preservePartial();
            throw new SchemaLoomException("cannot close CSV target", e);
        } finally {
            writer = null;
        }
    }

    /** 保留未完成的 .part 文件供排查和人工恢复。 */
    private void preservePartial() {
        if (part == null) return;
        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {
            // 保留原始写入异常，无法移动时由调用方根据目标目录排查。
        } finally {
            writer = null;
        }
    }
}
