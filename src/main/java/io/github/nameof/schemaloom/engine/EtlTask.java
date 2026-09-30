package io.github.nameof.schemaloom.engine;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.metadata.TableInfo;
import io.github.nameof.schemaloom.transform.FieldMapping;
import io.github.nameof.schemaloom.internal.LoggingSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;

public final class EtlTask implements Callable<EtlResult> {
    private static final Logger log = LoggerFactory.getLogger(EtlTask.class);
    private final Source source;
    private final Target target;
    private final Transformer transformer;
    private final List<FieldMapping> mappings;
    private final ErrorPolicy errorPolicy;
    private final TargetMode targetMode;
    private final Object context;
    private final EtlTaskListener listener;
    private final ListenerErrorHandler listenerErrorHandler;

    private final long[] readCounter = {0}, transformedCounter = {0}, filteredCounter = {0}, writtenCounter = {0}, failedCounter = {0}, batchCounter = {0};

    private EtlTask(Builder builder) {
        source = builder.source;
        target = builder.target;
        transformer = builder.transformer;
        mappings = builder.mappings;
        errorPolicy = builder.errorPolicy;
        targetMode = builder.targetMode;
        context = builder.context;
        listener = builder.listener;
        listenerErrorHandler = builder.listenerErrorHandler;
    }

    public EtlResult run() {
        readCounter[0] = transformedCounter[0] = filteredCounter[0] = writtenCounter[0] = failedCounter[0] = batchCounter[0] = 0;
        Instant start = Instant.now();
        String runId = LoggingSupport.runId();
        log.info("ETL任务开始 runId={} errorPolicy={} targetMode={}", runId, errorPolicy, targetMode);
        long total = -1L, read = 0, transformed = 0, filtered = 0, written = 0, failed = 0;
        List<EtlError> errors = new ArrayList<EtlError>();
        EtlStatus status = EtlStatus.SUCCESS;
        try {
            total = source.count();
            if (total < 0) total = -1L;
            log.debug("ETL任务读取总数 runId={} total={}", runId, total);
            notifyStarted(new EtlProgress(total, 0, 0, 0, 0, 0, 0, start));

            SchemaDescriptor sourceDescriptor = source.schema();
            TableInfo mappedTable = sourceDescriptor.getTableInfo() == null ? null
                    : FieldMapping.mapTableInfo(sourceDescriptor.getTableInfo(), mappings);
            RecordSchema targetSchema = mappedTable == null
                    ? FieldMapping.mapSchema(sourceDescriptor.getSchema(), mappings) : mappedTable.toRecordSchema();
            SchemaDescriptor targetDescriptor = mappedTable == null
                    ? SchemaDescriptor.of(targetSchema) : SchemaDescriptor.of(mappedTable);
            for (EtlError error : target.prepare(targetDescriptor, targetMode))
                addError(errors, error);
            final RecordSchema schema = targetSchema;
            final long observedTotal = total;
            source.read(batch -> {
                if (Thread.currentThread().isInterrupted()) throw new SchemaLoomException("interrupted");
                List<DataRecord> out = new ArrayList<DataRecord>();
                for (DataRecord r : batch.getRecords()) {
                    readCounter[0]++;
                    try {
                        if (transformer != null) {
                            TransformResult tr = transformer.transform(r);
                            if (tr == null || tr.isDropped()) {
                                filteredCounter[0]++;
                                continue;
                            }
                            r = tr.getRecord();
                        }
                        out.add(FieldMapping.mapRecord(r, schema, mappings));
                        transformedCounter[0]++;
                    } catch (Throwable e) {
                        failedCounter[0]++;
                        addError(errors, new EtlError(readCounter[0], "transform", e));
                        if (errorPolicy == ErrorPolicy.FAIL_FAST) throw new SchemaLoomException("transform failed", e);
                        if (errorPolicy == ErrorPolicy.SKIP_BATCH) {
                            out.clear();
                            break;
                        }
                    }
                }
                if (!out.isEmpty()) {
                    try {
                        BatchWriteResult wr = target.write(new RecordBatch(schema, out));
                        writtenCounter[0] += wr.getWritten();
                        failedCounter[0] += wr.getFailed();
                    } catch (Throwable e) {
                        if (errorPolicy == ErrorPolicy.ISOLATE_AND_CONTINUE) {
                            log.warn("批次写入失败，降级为逐行写入 runId={} batch={} message={}", runId,
                                    batchCounter[0] + 1, LoggingSupport.message(e));
                            for (DataRecord r : out) {
                                try {
                                    BatchWriteResult single = target.write(new RecordBatch(schema, Collections.singletonList(r)));
                                    writtenCounter[0] += single.getWritten();
                                    failedCounter[0] += single.getFailed();
                                } catch (Throwable one) {
                                    failedCounter[0]++;
                                    addError(errors, new EtlError(readCounter[0], "write", one));
                                }
                            }
                        } else if (errorPolicy == ErrorPolicy.SKIP_BATCH) {
                            log.warn("批次写入失败，跳过批次 runId={} batch={} size={} message={}", runId,
                                    batchCounter[0] + 1, out.size(), LoggingSupport.message(e));
                            failedCounter[0] += out.size();
                            addError(errors, new EtlError(readCounter[0], "write", e));
                        } else {
                            addError(errors, new EtlError(readCounter[0], "write", e));
                            throw new SchemaLoomException("write failed", e);
                        }
                    }
                }
                batchCounter[0]++;
                log.debug("ETL批次完成 runId={} batch={} read={} written={} failed={}", runId,
                        batchCounter[0], readCounter[0], writtenCounter[0], failedCounter[0]);
                notifyProgress(new EtlProgress(observedTotal, batchCounter[0], readCounter[0],
                        transformedCounter[0], filteredCounter[0], writtenCounter[0], failedCounter[0], start));
            });
            read = readCounter[0];
            transformed = transformedCounter[0];
            filtered = filteredCounter[0];
            written = writtenCounter[0];
            failed = failedCounter[0];
            if (failed > 0) {
                status = written > 0 ? EtlStatus.PARTIAL : EtlStatus.FAILED;
            }
        } catch (Throwable e) {
            read = readCounter[0];
            transformed = transformedCounter[0];
            filtered = filteredCounter[0];
            written = writtenCounter[0];
            failed = failedCounter[0];
            if (Thread.currentThread().isInterrupted() || e.getMessage() != null && e.getMessage().contains("interrupted")) {
                status = EtlStatus.CANCELLED;
                Thread.currentThread().interrupt();
            } else {
                status = EtlStatus.FAILED;
                addError(errors, new EtlError(read, "task", e));
                log.error("ETL任务失败 runId={} stage=task message={}", runId, LoggingSupport.message(e),
                        LoggingSupport.safe(e));
            }
        } finally {
            try {
                if (target != null) target.close();
            } catch (Throwable e) {
                addError(errors, new EtlError(read, "close-target", e));
                log.error("目标资源关闭失败 runId={} cleanupFailed=true message={}", runId,
                        LoggingSupport.message(e), LoggingSupport.safe(e));
            }
            try {
                if (source != null) source.close();
            } catch (Throwable e) {
                addError(errors, new EtlError(read, "close-source", e));
                log.error("源资源关闭失败 runId={} cleanupFailed=true message={}", runId,
                        LoggingSupport.message(e), LoggingSupport.safe(e));
            }
        }
        Instant ended = Instant.now();
        ReadStatistics readStatistics = source instanceof ReadStatisticsProvider
                ? ((ReadStatisticsProvider) source).getReadStatistics()
                : new ReadStatistics(read, 0, Collections.<String, Long>emptyMap());
        WriteStatistics writeStatistics = target instanceof WriteStatisticsProvider
                ? ((WriteStatisticsProvider) target).getWriteStatistics()
                : new WriteStatistics(written, 0, failed, Collections.<String, Long>emptyMap());
        EtlResult result = new EtlResult(status, read, transformed, filtered, written, failed, start, ended, errors,
                readStatistics, writeStatistics);
        log.info("ETL任务结束 runId={} status={} elapsedMs={} read={} transformed={} filtered={} written={} failed={}",
                runId, status, result.getElapsedMillis(), read, transformed, filtered, written, failed);
        notifyCompleted(result);
        return result;
    }

    private void notifyStarted(EtlProgress progress) {
        if (listener == null) return;
        try {
            listener.onStarted(context, progress);
        } catch (Throwable e) {
            notifyListenerError(ListenerCallback.STARTED, e);
        }
    }

    private void notifyProgress(EtlProgress progress) {
        if (listener == null) return;
        try {
            listener.onProgress(context, progress);
        } catch (Throwable e) {
            notifyListenerError(ListenerCallback.PROGRESS, e);
        }
    }

    private void notifyCompleted(EtlResult result) {
        if (listener == null) return;
        try {
            listener.onCompleted(context, result);
        } catch (Throwable e) {
            notifyListenerError(ListenerCallback.COMPLETED, e);
        }
    }

    private void notifyListenerError(ListenerCallback callback, Throwable error) {
        if (listenerErrorHandler == null) return;
        try {
            listenerErrorHandler.onError(callback, context, error);
        } catch (Throwable ignored) {
        }
    }

    private static void addError(List<EtlError> errors, EtlError e) {
        if (errors.size() < 100) errors.add(e);
    }

    public EtlResult call() {
        return run();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Source source;
        private Target target;
        private Transformer transformer;
        private List<FieldMapping> mappings = Collections.emptyList();
        private ErrorPolicy errorPolicy = ErrorPolicy.ISOLATE_AND_CONTINUE;
        private TargetMode targetMode = TargetMode.APPEND;
        private Object context;
        private EtlTaskListener listener;
        private ListenerErrorHandler listenerErrorHandler;

        public Builder source(Source source) { this.source = source; return this; }
        public Builder target(Target target) { this.target = target; return this; }
        public Builder transformer(Transformer transformer) { this.transformer = transformer; return this; }
        public Builder mappings(List<FieldMapping> mappings) { this.mappings = mappings; return this; }
        public Builder errorPolicy(ErrorPolicy errorPolicy) { this.errorPolicy = errorPolicy; return this; }
        public Builder targetMode(TargetMode targetMode) { this.targetMode = targetMode; return this; }
        public Builder context(Object context) { this.context = context; return this; }
        public Builder listener(EtlTaskListener listener) { this.listener = listener; return this; }
        public Builder listenerErrorHandler(ListenerErrorHandler handler) { this.listenerErrorHandler = handler; return this; }

        public EtlTask build() {
            if (source == null) throw new IllegalArgumentException("source is required");
            if (target == null) throw new IllegalArgumentException("target is required");
            if (errorPolicy == null) throw new IllegalArgumentException("errorPolicy must not be null");
            if (targetMode == null) throw new IllegalArgumentException("targetMode must not be null");
            if (mappings == null) throw new IllegalArgumentException("mappings must not be null");
            return new EtlTask(this);
        }
    }
}
