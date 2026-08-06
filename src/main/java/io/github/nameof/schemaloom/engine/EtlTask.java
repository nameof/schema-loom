package io.github.nameof.schemaloom.engine;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.transform.FieldMapping;
import lombok.Builder;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;

@Builder
public final class EtlTask implements Callable<EtlResult> {
    private final Source source;
    private final Target target;
    private final Transformer transformer;
    private final List<FieldMapping> mappings;
    private final ErrorPolicy errorPolicy;
    private final TargetMode targetMode;
    private final Object context;
    private final EtlTaskListener listener;
    private final ListenerErrorHandler listenerErrorHandler;

    public EtlResult run() {
        readCounter[0] = transformedCounter[0] = filteredCounter[0] = writtenCounter[0] = failedCounter[0] = batchCounter[0] = 0;
        Instant start = Instant.now();
        long total = -1L, read = 0, transformed = 0, filtered = 0, written = 0, failed = 0;
        List<EtlError> errors = new ArrayList<EtlError>();
        EtlStatus status = EtlStatus.SUCCESS;
        try {
            total = source.count();
            if (total < 0) total = -1L;
            notifyStarted(new EtlProgress(total, 0, 0, 0, 0, 0, 0, start));

            RecordSchema targetSchema = FieldMapping.mapSchema(source.schema(), mappings);
            target.prepare(targetSchema, targetMode);
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
                        addError(errors, new EtlError(readCounter[0], "write", e));
                        if (errorPolicy == ErrorPolicy.ISOLATE_AND_CONTINUE) {
                            for (DataRecord r : out) {
                                try {
                                    target.write(new RecordBatch(schema, Collections.singletonList(r)));
                                    writtenCounter[0]++;
                                } catch (Throwable one) {
                                    failedCounter[0]++;
                                    addError(errors, new EtlError(readCounter[0], "write", one));
                                }
                            }
                        } else if (errorPolicy == ErrorPolicy.SKIP_BATCH) {
                            failedCounter[0] += out.size();
                        } else {
                            throw new SchemaLoomException("write failed", e);
                        }
                    }
                }
                batchCounter[0]++;
                notifyProgress(new EtlProgress(observedTotal, batchCounter[0], readCounter[0],
                        transformedCounter[0], filteredCounter[0], writtenCounter[0], failedCounter[0], start));
            });
            read = readCounter[0];
            transformed = transformedCounter[0];
            filtered = filteredCounter[0];
            written = writtenCounter[0];
            failed = failedCounter[0];
            if (failed > 0) status = EtlStatus.PARTIAL;
        } catch (Throwable e) {
            read = readCounter[0];
            transformed = transformedCounter[0];
            filtered = filteredCounter[0];
            written = writtenCounter[0];
            failed = failedCounter[0] + 1;
            if (Thread.currentThread().isInterrupted() || e.getMessage() != null && e.getMessage().contains("interrupted")) {
                status = EtlStatus.CANCELLED;
                Thread.currentThread().interrupt();
            } else {
                status = EtlStatus.FAILED;
                addError(errors, new EtlError(read, "task", e));
            }
        } finally {
            try {
                if (target != null) target.close();
            } catch (Throwable e) {
                addError(errors, new EtlError(read, "close-target", e));
            }
            try {
                if (source != null) source.close();
            } catch (Throwable e) {
                addError(errors, new EtlError(read, "close-source", e));
            }
        }
        Instant ended = Instant.now();
        EtlResult result = new EtlResult(status, read, transformed, filtered, written, failed, start, ended, errors);
        notifyCompleted(result);
        return result;
    }

    private final long[] readCounter = {0}, transformedCounter = {0}, filteredCounter = {0}, writtenCounter = {0}, failedCounter = {0}, batchCounter = {0};

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
}
