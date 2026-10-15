package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.source.JdbcTypes;
import io.github.nameof.schemaloom.internal.LoggingSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import schemacrawler.schema.*;
import schemacrawler.schemacrawler.LimitOptionsBuilder;
import schemacrawler.schemacrawler.LoadOptionsBuilder;
import schemacrawler.schemacrawler.SchemaCrawlerOptionsBuilder;
import schemacrawler.schemacrawler.SchemaInfoLevelBuilder;
import schemacrawler.tools.utility.SchemaCrawlerUtility;
import us.fatehi.utility.datasource.DatabaseConnectionSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 数据库元数据读取服务，封装 SchemaCrawler 并提供统一的元数据访问 API。
 * 支持读取数据库、Schema、表、列、索引等元数据，以及表和 Schema 的统计信息。
 */
public final class DatabaseMetadataService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseMetadataService.class);
    private static final Object SCHEMA_CRAWLER_COMPATIBILITY_LOCK = new Object();
    private static final String WITHOUT_DATABASE_PLUGIN_PROPERTY = "SC_WITHOUT_DATABASE_PLUGIN";

    public DatabaseInfo getDatabaseInfo(ConnectionProvider provider) {
        Catalog catalog = catalog(provider, null, false);
        return new DatabaseInfo(catalog.getDatabaseInfo().getDatabaseProductName(), catalog.getDatabaseInfo().getDatabaseProductVersion(),
                catalog.getJdbcDriverInfo().getDriverName(), catalog.getJdbcDriverInfo().getDriverVersion(), catalog.getJdbcDriverInfo().getConnectionUrl());
    }

    public List<CatalogInfo> listCatalogs(ConnectionProvider provider) {
        Set<String> names = new LinkedHashSet<>();
        for (Schema schema : catalog(provider, null).getSchemas())
            if (schema.getCatalogName() != null)
                names.add(schema.getCatalogName());
        List<CatalogInfo> out = new ArrayList<>();
        for (String name : names)
            out.add(new CatalogInfo(name));
        return out;
    }

    public List<SchemaInfo> listSchemas(ConnectionProvider provider) {
        return listSchemas(provider, null);
    }

    /** 按 catalog、schema 范围过滤读取 schema，避免调用方在全量结果上二次过滤。 */
    public List<SchemaInfo> listSchemas(ConnectionProvider provider, MetadataQuery query) {
        List<SchemaInfo> out = new ArrayList<SchemaInfo>();
        for (Schema schema : catalog(provider, query, false).getSchemas()) {
            if (query == null || matches(query.getCatalog(), schema.getCatalogName()) && matches(query.getSchema(), schema.getName()))
                out.add(new SchemaInfo(schema.getCatalogName(), schema.getName()));
        }
        log.debug("数据库 Schema 读取完成 runId={} count={}", LoggingSupport.currentRunId(), out.size());
        return out;
    }

    /** 按 catalog、schema、表名模式匹配读取表元数据，包含列、索引、外键、约束等完整信息。 */
    public List<TableInfo> listTables(ConnectionProvider provider, MetadataQuery query) {
        Pattern pattern = Pattern.compile(query.getTablePattern().replace("%", ".*").replace("_", "."), Pattern.CASE_INSENSITIVE);
        List<TableInfo> out = new ArrayList<TableInfo>();
        for (Table table : catalog(provider, query, true).getTables()) {
            Schema schema = table.getSchema();
            if (!matches(query.getCatalog(), schema.getCatalogName()) || !matches(query.getSchema(), schema.getName()) || !pattern.matcher(table.getName()).matches()) continue;
            TableInfo mapped = map(table);
            out.add(enrichGeneratedColumns(provider, mapped));
        }
        log.debug("数据库表元数据读取完成 runId={} count={}", LoggingSupport.currentRunId(), out.size());
        return out;
    }

    /** 读取指定表的完整元数据，表不存在时抛出异常。 */
    public TableInfo getTable(ConnectionProvider provider, QualifiedTableName name) {
        for (TableInfo table : listTables(provider, new MetadataQuery(name.getCatalog(), name.getSchema(), name.getTable())))
            if (table.getName().getTable().equalsIgnoreCase(name.getTable())) return table;
        throw new SchemaLoomException("table not found: " + name.getTable());
    }

    /** 读取单表系统统计；不执行 COUNT(*)，行数可能是数据库估算值。 */
    public TableStatistics getTableStatistics(ConnectionProvider provider, QualifiedTableName name) {
        if (provider == null || name == null) throw new IllegalArgumentException("连接和表名不能为空");
        try { return new JdbcDatabaseStatisticsReader(provider).table(name); }
        catch (SchemaLoomException e) { throw e; }
        catch (Exception e) { throw new SchemaLoomException("cannot read table statistics", e); }
    }

    /** 读取 Schema 系统统计；只统计普通表，长度单位为字节。 */
    public SchemaStatistics getSchemaStatistics(ConnectionProvider provider, SchemaInfo name) {
        if (provider == null || name == null) throw new IllegalArgumentException("连接和 Schema 不能为空");
        try { return new JdbcDatabaseStatisticsReader(provider).schema(name); }
        catch (SchemaLoomException e) { throw e; }
        catch (Exception e) { throw new SchemaLoomException("cannot read schema statistics", e); }
    }

    /**
     * 在 SchemaCrawler 采集阶段限制 schema 和表名；下游仍做精确比较，兼容各驱动对名称的映射差异。
     */
    private Catalog catalog(ConnectionProvider provider, MetadataQuery query) {
        return catalog(provider, query, true);
    }

    private Catalog catalog(ConnectionProvider provider, MetadataQuery query, boolean loadTables) {
        try {
            schemacrawler.schemacrawler.SchemaCrawlerOptions options = SchemaCrawlerOptionsBuilder.newSchemaCrawlerOptions();
            if (query != null) {
                LimitOptionsBuilder limits = LimitOptionsBuilder.builder();
                Pattern schemaPattern = query.getCatalog() == null ? null
                        : namespacePattern(query.getCatalog(), query.getSchema());
                if (schemaPattern != null) limits.includeSchemas(schemaPattern);
                if (loadTables) limits.includeTables(sqlPattern(query.getTablePattern()));
                options = options.withLimitOptions(limits.toOptions());
            }
            if (!loadTables) {
                options = options.withLoadOptions(LoadOptionsBuilder.builder()
                        .withSchemaInfoLevel(SchemaInfoLevelBuilder.builder().withoutTables().toOptions())
                        .toOptions());
            }
            return loadCatalogWithCompatibility(provider, options);
        } catch (Exception e) {
            throw new SchemaLoomException("cannot read database metadata with SchemaCrawler", e);
        }
    }

    /** SchemaCrawler 对 PostgreSQL/DB2 会要求额外 connector；通用 JDBC 模式无需上层配置。 */
    private Catalog loadCatalogWithCompatibility(ConnectionProvider provider,
                                                   schemacrawler.schemacrawler.SchemaCrawlerOptions options) throws Exception {
        String product = provider.getConnection().getMetaData().getDatabaseProductName();
        String productLower = product == null ? "" : product.toLowerCase(Locale.ENGLISH);
        boolean needsPluginWorkaround = productLower.contains("postgresql") || productLower.contains("db2") || productLower.contains("kingbase");
        if (!needsPluginWorkaround)
            return SchemaCrawlerUtility.getCatalog(connectionSource(provider), options);
        String pluginName = productLower.contains("db2") ? "db2" : productLower.contains("kingbase") ? "postgresql" : "postgresql";
        synchronized (SCHEMA_CRAWLER_COMPATIBILITY_LOCK) {
            String previous = System.getProperty(WITHOUT_DATABASE_PLUGIN_PROPERTY);
            try {
                System.setProperty(WITHOUT_DATABASE_PLUGIN_PROPERTY, pluginName);
                return SchemaCrawlerUtility.getCatalog(connectionSource(provider), options);
            } finally {
                if (previous == null) System.clearProperty(WITHOUT_DATABASE_PLUGIN_PROPERTY);
                else System.setProperty(WITHOUT_DATABASE_PLUGIN_PROPERTY, previous);
            }
        }
    }
    private Pattern namespacePattern(String catalog, String schema) {
        if (catalog == null && schema == null) return null;
        String name = schema == null ? catalog : schema;
        return Pattern.compile("(?:^|.*/)" + Pattern.quote(name) + "(?:/.*)?$", Pattern.CASE_INSENSITIVE);
    }

    private Pattern sqlPattern(String value) {
        StringBuilder regex = new StringBuilder("^.*");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '%') regex.append(".*");
            else if (character == '_') regex.append('.');
            else regex.append(Pattern.quote(String.valueOf(character)));
        }
        return Pattern.compile(regex.append(".*$").toString(), Pattern.CASE_INSENSITIVE);
    }

    private String join(List<String> values, String delimiter) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(delimiter);
            result.append(value);
        }
        return result.toString();
    }

    private DatabaseConnectionSource connectionSource(final ConnectionProvider provider) {
        final Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("close".equals(method.getName())) return null;
                        try { return method.invoke(provider.getConnection(), args); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    }
                });
        return new DatabaseConnectionSource() {
            public Connection get() { return connection; }
            public boolean releaseConnection(Connection connection) { return false; }
            public void setFirstConnectionInitializer(java.util.function.Consumer<Connection> initializer) { initializer.accept(get()); }
            public void close() { }
        };
    }

    private boolean matches(String expected, String actual) { return expected == null || (actual != null && expected.equalsIgnoreCase(actual)); }

    private TableInfo map(Table table) {
        QualifiedTableName name = name(table);
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        for (Column column : table.getColumns()) {
            Integer typeNumber = column.getColumnDataType().getJavaSqlType().getVendorTypeNumber();
            if (typeNumber == null || typeNumber == java.sql.Types.NULL)
                throw new SchemaLoomException("列缺少有效 JDBC 类型: " + column.getName());
            // Oracle NUMBER 在通用 SchemaCrawler 模式下可能被误报为 BIT。
            if (typeNumber == java.sql.Types.BIT
                    && "NUMBER".equalsIgnoreCase(column.getColumnDataType().getDatabaseSpecificTypeName()))
                typeNumber = java.sql.Types.NUMERIC;
            LogicalType logicalType = JdbcTypes.logical(typeNumber);
            Integer size = column.getSize();
            Integer length = logicalType == LogicalType.STRING || logicalType == LogicalType.BINARY ? size : null;
            Integer precision = logicalType == LogicalType.DECIMAL ? size : null;
            Integer scale = logicalType == LogicalType.DECIMAL ? column.getDecimalDigits() : null;
            String value = column.getDefaultValue();
            FieldSchema field = new FieldSchema(column.getName(), logicalType, column.isNullable(), length, precision, scale);
            // DB2 等数据库将 identity 列也视为 generated，但 identity 列没有生成表达式，
            // 需通过 !column.isAutoIncremented() 排除，避免后续迁移时误判为计算列。
            ColumnInfo info = new ColumnInfo(field, column.getColumnDataType().getDatabaseSpecificTypeName(), column.getRemarks(),
                    column.getOrdinalPosition(), typeNumber,
                    column.isGenerated() ? null : value, column.isGenerated() ? value : null,
                    column.isAutoIncremented(), column.isGenerated() && !column.isAutoIncremented());
            columns.add(info);
        }
        PrimaryKeyInfo primaryKey = primaryKey(table);
        return new TableInfo(name, table.getTableType().isView(), table.getTableType().getTableType(), columns,
                primaryKey, indexes(table), foreignKeys(table), constraints(table), table.getRemarks());
    }

    /**
     * 补充生成列表达式：SchemaCrawler 对 MySQL/MariaDB/PostgreSQL/SQL Server 不总是暴露生成列定义，
     * 通过直接查询系统表补齐，确保迁移时能正确识别和重建生成列。
     */
    private TableInfo enrichGeneratedColumns(ConnectionProvider provider, TableInfo table) {
        try {
            String product = provider.getConnection().getMetaData().getDatabaseProductName();
            if (product == null) return table;
            boolean mysql = cn.hutool.core.util.StrUtil.containsAnyIgnoreCase(product, "mysql", "mariadb");
            boolean postgres = product.toLowerCase(Locale.ENGLISH).contains("postgresql");
            boolean kingbase = product.toLowerCase(Locale.ENGLISH).contains("kingbase");
            boolean sqlServer = product.toLowerCase(Locale.ENGLISH).contains("sql server");
            boolean db2 = product.toLowerCase(Locale.ENGLISH).contains("db2");
            if (!mysql && !postgres && !kingbase && !sqlServer && !db2) return table;
            Map<String, String> expressions = new HashMap<String, String>();
            String sql = mysql
                    ? "SELECT COLUMN_NAME, GENERATION_EXPRESSION FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND EXTRA LIKE '%GENERATED%'"
                    : "SELECT a.attname, pg_get_expr(d.adbin, d.adrelid) FROM pg_catalog.pg_attribute a "
                    + "JOIN pg_catalog.pg_class c ON c.oid = a.attrelid "
                    + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                    + "LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum "
                    + "WHERE n.nspname = ? AND c.relname = ? AND a.attgenerated <> ''";
            if (sqlServer) {
                sql = "SELECT col.name, cc.definition FROM sys.computed_columns cc "
                        + "JOIN sys.columns col ON col.object_id = cc.object_id AND col.column_id = cc.column_id "
                        + "JOIN sys.tables t ON t.object_id = cc.object_id "
                        + "JOIN sys.schemas s ON s.schema_id = t.schema_id "
                        + "WHERE s.name = ? AND t.name = ?";
            }
            if (db2) {
                // DB2 的 IDENTITY 列（如 GENERATED BY DEFAULT AS IDENTITY）在 SYSCAT.COLUMNS 中
                // TEXT 字段也可能非空（值为 '-'），需通过 IDENTITY 和 GENERATED 字段联合过滤，
                // 仅取真正的计算列（GENERATED <> ' ' 且 IDENTITY = 'N'）。
                sql = "SELECT COLNAME, TEXT FROM SYSCAT.COLUMNS "
                        + "WHERE TABSCHEMA = ? AND TABNAME = ? AND IDENTITY = 'N' AND GENERATED <> ' '";
            }
            if (kingbase) {
                // KingbaseES 的 char 类型对 <> '' 比较行为异常，需使用 = 's' 精确匹配存储型生成列。
                sql = "SELECT a.attname, pg_get_expr(d.adbin, d.adrelid) FROM pg_catalog.pg_attribute a "
                        + "JOIN pg_catalog.pg_class c ON c.oid = a.attrelid "
                        + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                        + "LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum "
                        + "WHERE n.nspname = ? AND c.relname = ? AND a.attgenerated = 's'";
            }
            PreparedStatement statement = provider.getConnection().prepareStatement(sql);
            try {
                String namespaceParam;
                if (postgres || kingbase || sqlServer) namespaceParam = table.getName().getSchema();
                else if (db2) namespaceParam = table.getName().getSchema() != null ? table.getName().getSchema() : table.getName().getCatalog();
                else namespaceParam = table.getName().getCatalog();
                statement.setString(1, namespaceParam);
                statement.setString(2, table.getName().getTable());
                ResultSet result = statement.executeQuery();
                try {
                    while (result.next()) expressions.put(result.getString(1).toLowerCase(Locale.ENGLISH), result.getString(2));
                } finally {
                    result.close();
                }
            } finally {
                statement.close();
            }
            if (expressions.isEmpty()) return table;
            List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
            for (ColumnInfo column : table.getColumns()) {
                String expression = expressions.get(column.getName().toLowerCase(Locale.ENGLISH));
                if (expression == null) {
                    columns.add(column);
                } else {
                    columns.add(new ColumnInfo(column.getFieldSchema(), column.getTypeName(), column.getRemarks(),
                            column.getOrdinal(), column.getJdbcType(), column.getDefaultValue(), expression,
                            column.isAutoIncremented(), true));
                }
            }
            return new TableInfo(table.getName(), table.isView(), table.getType(), columns, table.getPrimaryKey(),
                    table.getIndexes(), table.getForeignKeys(), table.getConstraints(), table.getRemarks());
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot read generated column metadata", e);
        }
    }
    private QualifiedTableName name(Table table) {
        Schema schema = table.getSchema();
        return new QualifiedTableName(schema.getCatalogName(), schema.getName(), table.getName());
    }

    private PrimaryKeyInfo primaryKey(Table table) {
        PrimaryKey key = table.getPrimaryKey();
        if (key == null) return null;
        List<String> columns = new ArrayList<String>();
        for (TableConstraintColumn column : key.getConstrainedColumns()) columns.add(column.getName());
        return new PrimaryKeyInfo(key.getName(), columns);
    }

    /**
     * 读取表的索引信息，过滤主键索引。
     * 主键约束已隐式创建索引，不应作为独立索引出现在元数据中，所有下游消费者（target、statistics 等）均受益于此过滤。
     */
    private List<IndexInfo> indexes(Table table) {
        List<IndexInfo> out = new ArrayList<IndexInfo>();
        PrimaryKey primaryKey = table.getPrimaryKey();
        for (Index index : table.getIndexes()) {
            // 跳过主键索引：主键约束已隐式创建索引，避免重复导致歧义和创建错误
            if (isPrimaryKeyIndex(index, primaryKey))
                continue;
            List<String> columns = new ArrayList<String>();
            for (IndexColumn column : index.getColumns()) columns.add(column.getName());
            out.add(new IndexInfo(index.getName(), index.getIndexType().toString(), index.isUnique(), columns));
        }
        return out;
    }

    private boolean isPrimaryKeyIndex(Index index, PrimaryKey primaryKey) {
        if (primaryKey == null || !index.isUnique()) return false;
        List<? extends IndexColumn> indexColumns = index.getColumns();
        List<? extends TableConstraintColumn> pkColumns = primaryKey.getConstrainedColumns();
        if (indexColumns.size() != pkColumns.size()) return false;
        for (int i = 0; i < indexColumns.size(); i++)
            if (!indexColumns.get(i).getName().equalsIgnoreCase(pkColumns.get(i).getName())) return false;
        return true;
    }

    private List<ForeignKeyInfo> foreignKeys(Table table) {
        List<ForeignKeyInfo> out = new ArrayList<ForeignKeyInfo>();
        for (ForeignKey key : table.getImportedForeignKeys()) {
            List<String> columns = new ArrayList<String>(), referenced = new ArrayList<String>();
            for (ColumnReference reference : key.getColumnReferences()) { columns.add(reference.getForeignKeyColumn().getName()); referenced.add(reference.getPrimaryKeyColumn().getName()); }
            out.add(new ForeignKeyInfo(key.getName(), name(key.getPrimaryKeyTable()), columns, referenced, key.getUpdateRule().toString(), key.getDeleteRule().toString()));
        }
        return out;
    }

    private List<ConstraintInfo> constraints(Table table) {
        List<ConstraintInfo> out = new ArrayList<ConstraintInfo>();
        for (TableConstraint constraint : table.getTableConstraints()) {
            List<String> columns = new ArrayList<String>();
            for (TableConstraintColumn column : constraint.getConstrainedColumns()) columns.add(column.getName());
            out.add(new ConstraintInfo(constraint.getName(), constraint.getType().toString(), columns));
        }
        return out;
    }
}
