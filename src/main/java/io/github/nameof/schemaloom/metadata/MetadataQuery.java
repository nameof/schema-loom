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
     * MySQL 的数据库名属于 catalog，不应再作为 schema 传入。
     */
    public static MetadataQuery fromConnectionInfo(DatabaseConnectionInfo connectionInfo, String tablePattern) {
        if (connectionInfo == null) throw new IllegalArgumentException("连接配置不能为空");
        if (connectionInfo.getDatabaseType() == DatabaseType.MYSQL)
            return new MetadataQuery(connectionInfo.getDatabase(), null, tablePattern);
        return new MetadataQuery(connectionInfo.getCatalog(), connectionInfo.getSchema(), tablePattern);
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
