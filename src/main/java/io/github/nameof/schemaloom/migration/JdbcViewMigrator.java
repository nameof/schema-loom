package io.github.nameof.schemaloom.migration;

import io.github.nameof.schemaloom.api.SchemaLoomException;
import io.github.nameof.schemaloom.dialect.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.execution.JdbcExecutionAdapter;
import io.github.nameof.schemaloom.metadata.*;
import io.github.nameof.schemaloom.internal.LoggingSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Copies a view definition after its referenced target tables have been migrated.
 * Native view SQL is only safe to copy between identical database types.
 */
final class JdbcViewMigrator {
    private static final Logger log = LoggerFactory.getLogger(JdbcViewMigrator.class);
    private final DatabaseConnectionInfo source;
    private final DatabaseConnectionInfo target;
    private final JdbcDriverLoader loader;
    private final boolean createLoader;

    JdbcViewMigrator(DatabaseConnectionInfo source, DatabaseConnectionInfo target) {
        this(source, target, null, true);
    }

    JdbcViewMigrator(DatabaseConnectionInfo source, DatabaseConnectionInfo target, JdbcDriverLoader loader) {
        this(source, target, loader, false);
    }

    private JdbcViewMigrator(DatabaseConnectionInfo source, DatabaseConnectionInfo target, JdbcDriverLoader loader, boolean createLoader) {
        if (source == null || target == null) throw new IllegalArgumentException("source and target are required");
        if (!createLoader && loader == null) throw new IllegalArgumentException("jdbc driver loader is required");
        this.source = source;
        this.target = target;
        this.loader = loader;
        this.createLoader = createLoader;
    }

    /**
     * Creates {@code targetView} from {@code sourceView}; an existing target object is never replaced.
     * Failures are reported through {@link SchemaLoomException}.
     */
    void migrate(String sourceView, String targetView) {
        if (source.getDatabaseType() != target.getDatabaseType())
            throw new SchemaLoomException("native view migration requires identical source and target database types");
        if (!sameNativeNamespace())
            throw new SchemaLoomException("native view migration requires matching source and target namespaces; "
                    + "rewrite the view definition explicitly for a different database or schema");

        ConnectionProvider sourceProvider = null;
        ConnectionProvider targetProvider = null;
        JdbcDriverLoader activeLoader = createLoader ? new JdbcDriverLoader() : loader;
        try {
            sourceProvider = JdbcConnectionFactory.open(source, activeLoader);
            targetProvider = JdbcConnectionFactory.open(target, activeLoader);
            QualifiedTableName sourceName = source.table(sourceView);
            QualifiedTableName targetName = target.table(targetView);
            TableInfo sourceInfo = new DatabaseMetadataService().getTable(sourceProvider, sourceName);
            if (!sourceInfo.isView()) throw new SchemaLoomException("source object is not a view: " + sourceView);

            DatabaseMetadataService metadata = new DatabaseMetadataService();
            if (!metadata.listTables(targetProvider, new MetadataQuery(targetName.getCatalog(), targetName.getSchema(), targetName.getTable())).isEmpty())
                throw new SchemaLoomException("target object already exists: " + targetView);

            DatabaseDialect dialect = new DialectRegistry().get(source.getDatabaseType());
            String definition = readDefinition(new JdbcExecutionAdapter(sourceProvider), dialect.viewDefinitionQuery(source, sourceName));
            log.debug("视图定义已读取 runId={} sourceView={}", LoggingSupport.currentRunId(), sourceView);
            new JdbcExecutionAdapter(targetProvider).execute("create target view",
                    dialect.createViewSql(dialect.quote(targetName), definition));
            log.debug("目标视图已创建 runId={} targetView={}", LoggingSupport.currentRunId(), targetView);
        } finally {
            if (targetProvider != null) targetProvider.close();
            if (sourceProvider != null) sourceProvider.close();
            if (createLoader) activeLoader.close();
        }
    }

    private String readDefinition(JdbcExecutionAdapter execution, ViewDefinitionQuery query) {
        return execution.query("read view definition", query.getSql(), query.getParameters(), 0, null,
                new JdbcExecutionAdapter.ResultSetHandler<String>() {
                    public String extractData(ResultSet result) throws SQLException {
                        if (!result.next() || result.getString(1) == null || result.getString(1).trim().isEmpty())
                            throw new SchemaLoomException("view definition is unavailable; verify metadata permissions");
                        return result.getString(1).trim();
                    }
                });
    }

    private boolean sameNativeNamespace() {
        if (source.getDatabaseType() == DatabaseType.ORACLE)
            return equalsIgnoreCase(source.getSchema(), target.getSchema());
        String sourceCatalog = source.getCatalog() == null ? source.getDatabase() : source.getCatalog();
        String targetCatalog = target.getCatalog() == null ? target.getDatabase() : target.getCatalog();
        return equalsIgnoreCase(sourceCatalog, targetCatalog)
                && equalsIgnoreCase(source.getSchema(), target.getSchema());
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
    }
}
