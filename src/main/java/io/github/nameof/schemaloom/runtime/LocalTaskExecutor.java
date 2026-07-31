package io.github.nameof.schemaloom.runtime;

import io.github.nameof.schemaloom.api.SchemaLoomException;
import io.github.nameof.schemaloom.engine.EtlTask;

import java.util.concurrent.*;

public final class LocalTaskExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;

    public LocalTaskExecutor() {
        this(Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4)), 100);
    }

    public LocalTaskExecutor(int threads, int queue) {
        if (threads <= 0 || queue <= 0) throw new IllegalArgumentException("threads and queue must be positive");
        executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(queue), new ThreadPoolExecutor.AbortPolicy());
    }

    public Future<io.github.nameof.schemaloom.api.EtlResult> submit(EtlTask task) {
        try {
            return executor.submit(task);
        } catch (RejectedExecutionException e) {
            throw new SchemaLoomException("task queue is full", e);
        }
    }

    public void close() {
        executor.shutdown();
    }
}
