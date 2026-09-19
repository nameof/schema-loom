package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.source.JdbcTypes;
import schemacrawler.schema.*;
import schemacrawler.schemacrawler.LimitOptionsBuilder;
import schemacrawler.schemacrawler.LoadOptionsBuilder;
import schemacrawler.schemacrawler.SchemaCrawlerOptionsBuilder;
import schemacrawler.schemacrawler.SchemaInfoLevelBuilder;
import schemacrawler.tools.utility.SchemaCrawlerUtility;
import us.fatehi.utility.datasource.DatabaseConnectionSource;

import java.sql.Connection;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;

/** SchemaCrawler 到 SchemaLoom 元数据 DTO 的稳定映射门面。 */
public final class DatabaseMetadataService {
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

    /** 按 catalog、schema 范围读取 schema，避免由调用方在全量结果上二次过滤。 */
    public List<SchemaInfo> listSchemas(ConnectionProvider provider, MetadataQuery query) {
        List<SchemaInfo> out = new ArrayList<SchemaInfo>();
        for (Schema schema : catalog(provider, query, false).getSchemas()) {
            if (query == null || matches(query.getCatalog(), schema.getCatalogName()) && matches(query.getSchema(), schema.getName()))
                out.add(new SchemaInfo(schema.getCatalogName(), schema.getName()));
        }
        return out;
    }

    public List<TableInfo> listTables(ConnectionProvider provider, MetadataQuery query) {
        Pattern pattern = Pattern.compile(query.getTablePattern().replace("%", ".*").replace("_", "."), Pattern.CASE_INSENSITIVE);
        List<TableInfo> out = new ArrayList<TableInfo>();
        for (Table table : catalog(provider, query, true).getTables()) {
            Schema schema = table.getSchema();
            if (!matches(query.getCatalog(), schema.getCatalogName()) || !matches(query.getSchema(), schema.getName()) || !pattern.matcher(table.getName()).matches()) continue;
            out.add(map(table));
        }
        return out;
    }

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
            return SchemaCrawlerUtility.getCatalog(connectionSource(provider), options);
        } catch (Exception e) {
            throw new SchemaLoomException("cannot read database metadata with SchemaCrawler", e);
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
            LogicalType logicalType = JdbcTypes.logical(typeNumber);
            Integer size = column.getSize();
            Integer length = logicalType == LogicalType.STRING || logicalType == LogicalType.BINARY ? size : null;
            Integer precision = logicalType == LogicalType.DECIMAL ? size : null;
            Integer scale = logicalType == LogicalType.DECIMAL ? column.getDecimalDigits() : null;
            String value = column.getDefaultValue();
            FieldSchema field = new FieldSchema(column.getName(), logicalType, column.isNullable(), length, precision, scale);
            ColumnInfo info = new ColumnInfo(field, column.getColumnDataType().getDatabaseSpecificTypeName(), column.getRemarks(),
                    column.getOrdinalPosition(), typeNumber,
                    column.isGenerated() ? null : value, column.isGenerated() ? value : null,
                    column.isAutoIncremented(), column.isGenerated());
            columns.add(info);
        }
        PrimaryKeyInfo primaryKey = primaryKey(table);
        return new TableInfo(name, table.getTableType().isView(), table.getTableType().getTableType(), columns,
                primaryKey, indexes(table), foreignKeys(table), constraints(table), table.getRemarks());
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

    private List<IndexInfo> indexes(Table table) {
        List<IndexInfo> out = new ArrayList<IndexInfo>();
        for (Index index : table.getIndexes()) {
            List<String> columns = new ArrayList<String>();
            for (IndexColumn column : index.getColumns()) columns.add(column.getName());
            out.add(new IndexInfo(index.getName(), index.getIndexType().toString(), index.isUnique(), columns));
        }
        return out;
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
