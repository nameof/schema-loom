package io.github.nameof.schemaloom.target;

import io.github.nameof.schemaloom.api.*;

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
    private Path partial;

    public CsvTarget(Path path) {
        this(path, StandardCharsets.UTF_8, ',');
    }

    public CsvTarget(Path path, Charset charset, char delimiter) {
        this.path = Objects.requireNonNull(path, "path");
        this.charset = charset;
        this.delimiter = delimiter;
    }

    public void prepare(RecordSchema schema, TargetMode mode) {
        this.schema = schema;
        try {
            Path out = mode == TargetMode.REPLACE ? path.resolveSibling(path.getFileName() + ".part") : path;
            partial = mode == TargetMode.REPLACE ? path.resolveSibling(path.getFileName() + ".partial") : null;
            if (mode == TargetMode.APPEND && Files.exists(out)) {
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

    public BatchWriteResult write(RecordBatch batch) {
        try {
            for (DataRecord r : batch.getRecords()) {
                for (int i = 0; i < schema.getFields().size(); i++) {
                    if (i > 0) writer.write(delimiter);
                    Object v = r.get(i);
                    writer.write(escape(v == null ? "" : String.valueOf(v)));
                }
                writer.write("\n");
            }
            writer.flush();
            return new BatchWriteResult(batch.size(), 0);
        } catch (IOException e) {
            throw new SchemaLoomException("cannot write CSV", e);
        }
    }

    public void close() {
        if (writer == null) return;
        try {
            writer.close();
            if (partial != null) {
                try {
                    Files.move(path.resolveSibling(path.getFileName() + ".part"), path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(path.resolveSibling(path.getFileName() + ".part"), path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new SchemaLoomException("cannot close CSV target", e);
        }
    }
}
