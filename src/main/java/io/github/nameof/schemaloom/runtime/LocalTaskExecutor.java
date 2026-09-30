package io.github.nameof.schemaloom.runtime;

import io.github.nameof.schemaloom.api.SchemaLoomException;
import io.github.nameof.schemaloom.engine.EtlTask;

import java.util.concurrent.*;
import io.github.nameof.schemaloom.api.EtlResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalTaskExecutor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LocalTaskExecutor.class);
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
            Future<io.github.nameof.schemaloom.api.EtlResult> future = executor.submit(task);
            log.debug("ETL任务已提交 queueSize={}", executor.getQueue().size());
            return future;
        } catch (RejectedExecutionException e) {
            log.warn("ETL任务提交被拒绝 queueSize={}", executor.getQueue().size());
            throw new SchemaLoomException("task queue is full", e);
        }
    }

    public Future<EtlResult> submit(Callable<EtlResult> task) {
        if (task == null) throw new IllegalArgumentException("task is required");
        try {
            Future<EtlResult> future = executor.submit(task);
            log.debug("异步任务已提交 queueSize={}", executor.getQueue().size());
            return future;
        } catch (RejectedExecutionException e) {
            log.warn("异步任务提交被拒绝 queueSize={}", executor.getQueue().size());
            throw new SchemaLoomException("task queue is full", e);
        }
    }

    public void close() {
        executor.shutdown();
        log.debug("本地任务执行器已关闭");
    }
}
