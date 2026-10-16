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
     * MySQL/MariaDB 的数据库名属于 catalog，不应再作为 schema 传入；
     * 其他数据库优先使用显式配置的 catalog/schema，均未设置时回退到 database，
     * 避免 catalog 和 schema 同时为 null 导致扫描全部命名空间。
     */
    public static MetadataQuery fromConnectionInfo(DatabaseConnectionInfo connectionInfo, String tablePattern) {
        if (connectionInfo == null) throw new IllegalArgumentException("连接配置不能为空");
        if (connectionInfo.getDatabaseType() == DatabaseType.MYSQL || connectionInfo.getDatabaseType() == DatabaseType.MARIADB
                || connectionInfo.getDatabaseType() == DatabaseType.GBASE)
            return new MetadataQuery(connectionInfo.getDatabase(), null, tablePattern);
        if (connectionInfo.getDatabaseType() == DatabaseType.POSTGRESQL)
            return new MetadataQuery(connectionInfo.getDatabase(), connectionInfo.getSchema() == null ? "public" : connectionInfo.getSchema(), tablePattern);
        // KingbaseES 兼容 PostgreSQL 但 SchemaCrawler 的 schema 模式匹配不兼容，
        // 不设置 catalog/schema 过滤，由下游代码做精确匹配
        if (connectionInfo.getDatabaseType() == DatabaseType.KINGBASE)
            return new MetadataQuery(null, connectionInfo.getSchema() == null ? "public" : connectionInfo.getSchema(), tablePattern);
        if (connectionInfo.getDatabaseType() == DatabaseType.DB2) {
            // DB2 的默认 schema 由连接会话决定（可能是用户名或其他），显式设置可能指向不存在的 schema，
            // 因此未配置时传 null，让 SchemaCrawler 和驱动自动发现合适的 schema
            return new MetadataQuery(connectionInfo.getCatalog(), connectionInfo.getSchema(), tablePattern);
        }
        String catalog = connectionInfo.getCatalog();
        String schema = connectionInfo.getSchema();
        if (catalog == null && schema == null)
            return new MetadataQuery(connectionInfo.getDatabase(), null, tablePattern);

        return new MetadataQuery(catalog, schema, tablePattern);
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
