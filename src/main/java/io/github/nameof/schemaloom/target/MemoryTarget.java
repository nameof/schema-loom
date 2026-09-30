package io.github.nameof.schemaloom.target;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.internal.LoggingSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class MemoryTarget implements Target {
    private static final Logger log = LoggerFactory.getLogger(MemoryTarget.class);
    private RecordSchema schema;
    private final List<DataRecord> records = new ArrayList<DataRecord>();
    private boolean prepared;

    public List<EtlError> prepare(SchemaDescriptor descriptor, TargetMode mode) {
        this.schema = descriptor.getSchema();
        prepared = true;
        if (mode == TargetMode.REPLACE) records.clear();
        log.debug("内存目标准备完成 runId={} mode={} existingRows={}", LoggingSupport.currentRunId(), mode, records.size());
        return Collections.emptyList();
    }

    public BatchWriteResult write(RecordBatch batch) {
        if (!prepared || batch.getSchema() != schema) throw new SchemaLoomException("target is not prepared");
        records.addAll(batch.getRecords());
        log.debug("内存目标批次写入完成 runId={} rows={} totalRows={}",
                LoggingSupport.currentRunId(), batch.size(), records.size());
        return new BatchWriteResult(batch.size(), 0);
    }

    public List<DataRecord> getRecords() {
        return Collections.unmodifiableList(records);
    }

    public void close() {
    }
}
