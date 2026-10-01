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
     * MySQL 的数据库名属于 catalog，不应再作为 schema 传入；
     * 其他数据库优先使用显式配置的 catalog/schema，均未设置时回退到 database，
     * 避免 catalog 和 schema 同时为 null 导致扫描全部命名空间。
     */
    public static MetadataQuery fromConnectionInfo(DatabaseConnectionInfo connectionInfo, String tablePattern) {
        if (connectionInfo == null) throw new IllegalArgumentException("连接配置不能为空");
        if (connectionInfo.getDatabaseType() == DatabaseType.MYSQL)
            return new MetadataQuery(connectionInfo.getDatabase(), null, tablePattern);
        if (connectionInfo.getDatabaseType() == DatabaseType.POSTGRESQL)
            return new MetadataQuery(connectionInfo.getDatabase(), connectionInfo.getSchema() == null ? "public" : connectionInfo.getSchema(), tablePattern);
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
