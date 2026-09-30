package io.github.nameof.schemaloom.migration;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.internal.LoggingSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * 只复制 VIEW 定义，当前要求源、目标数据库类型及命名空间一致，尚不支持异库、跨数据库类型或跨 Schema 的 VIEW SQL 改写。
 */
public final class JdbcViewMigrationTask implements Callable<EtlResult> {
    private static final Logger log = LoggerFactory.getLogger(JdbcViewMigrationTask.class);
    private final DatabaseConnectionInfo source;
    private final DatabaseConnectionInfo target;
    private final String sourceView;
    private final String targetView;
    private final JdbcDriverLoader loader;

    public JdbcViewMigrationTask(DatabaseConnectionInfo source, DatabaseConnectionInfo target,
                                 String sourceView, String targetView) {
        this(source, target, sourceView, targetView, null);
    }

    public JdbcViewMigrationTask(DatabaseConnectionInfo source, DatabaseConnectionInfo target,
                                 String sourceView, String targetView, JdbcDriverLoader loader) {
        if (source == null || target == null) throw new IllegalArgumentException("source and target are required");
        if (sourceView == null || sourceView.trim().isEmpty()) throw new IllegalArgumentException("source view is required");
        if (targetView == null || targetView.trim().isEmpty()) throw new IllegalArgumentException("target view is required");
        this.source = source;
        this.target = target;
        this.sourceView = sourceView;
        this.targetView = targetView;
        this.loader = loader;
    }

    /** Creates the target view and reports failures through the same result model as EtlTask. */
    public EtlResult run() {
        Instant started = Instant.now();
        String runId = LoggingSupport.runId();
        String previousRunId = LoggingSupport.bindRunId(runId);
        List<EtlError> errors = new ArrayList<EtlError>();
        try {
        log.info("视图迁移开始 runId={} sourceView={} targetView={}", runId, sourceView, targetView);
        try {
            if (loader == null)
                new JdbcViewMigrator(source, target).migrate(sourceView, targetView);
            else
                new JdbcViewMigrator(source, target, loader).migrate(sourceView, targetView);
            EtlResult result = new EtlResult(EtlStatus.SUCCESS, 1, 0, 0, 1, 0, started, Instant.now(), errors);
            log.info("视图迁移结束 runId={} status={} elapsedMs={}", runId, result.getStatus(), result.getElapsedMillis());
            return result;
        } catch (Throwable e) {
            errors.add(new EtlError(0, "view-migration", e));
            log.error("视图迁移失败 runId={} message={}", runId, LoggingSupport.message(e), LoggingSupport.safe(e));
            EtlResult result = new EtlResult(EtlStatus.FAILED, 0, 0, 0, 0, 1, started, Instant.now(), errors);
            return result;
        }
        } finally {
            LoggingSupport.restoreRunId(previousRunId);
        }
    }

    public EtlResult call() {
        return run();
    }
}
