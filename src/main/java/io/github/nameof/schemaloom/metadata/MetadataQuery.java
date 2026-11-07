package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.driver.DatabaseType;

public final class MetadataQuery {
    private final String catalog, schema, tablePattern;

    public MetadataQuery(String catalog, String schema, String tablePattern) {
        this.catalog = catalog;
        this.schema = schema;
        this.tablePattern = tablePattern == null ? "%" : tablePattern;
    }

    /**
     * 根据连接配置创建默认元数据查询范围。
     * MySQL/MariaDB/GBase 的数据库名属于 catalog，不应再作为 schema 传入；
     * PostgreSQL / KingbaseES 未配置 schema 时默认 public；DB2 的默认 schema 由连接会话决定，未配置时不下推。
     * <p>
     * 其余数据库（Oracle、达梦、SQL Server 等）的 database 与 schema 不是同一概念 ——
     * Oracle 的 database 是服务名、达梦是实例名，把 database 当 catalog 下推会让
     * {@code DatabaseMetadataService} 的命名空间过滤剔除掉全部 schema，最终一张表都查不到。
     * 因此这里只下推调用方显式配置的 catalog/schema，两者都为空时不做限制。
     */
    public static MetadataQuery fromConnectionInfo(DatabaseConnectionInfo connectionInfo, String tablePattern) {
        if (connectionInfo == null) throw new IllegalArgumentException("连接配置不能为空");
        DatabaseType type = connectionInfo.getDatabaseType();
        if (type == DatabaseType.MYSQL || type == DatabaseType.MARIADB || type == DatabaseType.GBASE)
            return new MetadataQuery(connectionInfo.getDatabase(), null, tablePattern);
        if (type == DatabaseType.POSTGRESQL)
            // PostgreSQL 不能把 database 下推成 catalog：DatabaseMetadataService 的命名空间过滤
            // 拿 schema 名去匹配 SchemaCrawler 的「catalog.schema」全名，一旦 catalog 非空，
            // 模式「(?:^|.*/)public(?:/.*)?$」匹配不上「库名.public」，会把所有表都过滤掉。
            // 因此这里只透传显式配置的 catalog，未配置时保持 null（与 KingbaseES 一致）。
            return new MetadataQuery(connectionInfo.getCatalog(), connectionInfo.getSchema() == null ? "public" : connectionInfo.getSchema(), tablePattern);
        // KingbaseES 兼容 PostgreSQL 但 SchemaCrawler 的 schema 模式匹配不兼容，
        // 不设置 catalog/schema 过滤，由下游代码做精确匹配
        if (type == DatabaseType.KINGBASE)
            return new MetadataQuery(null, connectionInfo.getSchema() == null ? "public" : connectionInfo.getSchema(), tablePattern);
        // DB2 的默认 schema 由连接会话决定（可能是用户名或其他），显式设置可能指向不存在的 schema，
        // 因此未配置时传 null，让 SchemaCrawler 和驱动自动发现合适的 schema。
        return new MetadataQuery(connectionInfo.getCatalog(), connectionInfo.getSchema(), tablePattern);
    }

    /**
     * 按连接配置与表限定名确定查询范围：表名自带 catalog/schema 时优先，未指定的部分回退到连接默认值。
     * 主流程（Source/Target/视图迁移）统一走这个入口，避免「按名称精确查询」与「按方言推导命名空间」两套语义并存。
     * <p>
     * {@code connectionInfo} 为 null 时（例如只拿到 {@code ConnectionProvider} 的场合）不推导方言默认值，
     * 仅按表名自带的 catalog/schema 查询，避免凭空猜测命名空间。
     */
    public static MetadataQuery fromConnectionInfo(DatabaseConnectionInfo connectionInfo, QualifiedTableName table) {
        if (table == null) throw new IllegalArgumentException("表名不能为空");
        if (connectionInfo == null) return new MetadataQuery(table.getCatalog(), table.getSchema(), table.getTable());
        MetadataQuery scope = fromConnectionInfo(connectionInfo, table.getTable());
        if (table.getCatalog() == null && table.getSchema() == null) return scope;
        return new MetadataQuery(table.getCatalog() == null ? scope.getCatalog() : table.getCatalog(),
                table.getSchema() == null ? scope.getSchema() : table.getSchema(), table.getTable());
    }

    public String getCatalog() {
        return catalog;
    }

    public String getSchema() {
        return schema;
    }

    public String getTablePattern() {
        return tablePattern;
    }
}
