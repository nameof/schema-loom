package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.source.JdbcTypes;
import schemacrawler.schema.*;
import schemacrawler.schemacrawler.SchemaCrawlerOptionsBuilder;
import schemacrawler.tools.utility.SchemaCrawlerUtility;
import us.fatehi.utility.datasource.DatabaseConnectionSource;

import java.sql.Connection;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;

/** SchemaCrawler 到 SchemaLoom 元数据 DTO 的稳定映射门面。 */
public final class DatabaseMetadataService {
    public DatabaseInfo getDatabaseInfo(ConnectionProvider provider) {
        Catalog catalog = catalog(provider);
        return new DatabaseInfo(catalog.getDatabaseInfo().getDatabaseProductName(), catalog.getDatabaseInfo().getDatabaseProductVersion(),
                catalog.getJdbcDriverInfo().getDriverName(), catalog.getJdbcDriverInfo().getDriverVersion(), catalog.getJdbcDriverInfo().getConnectionUrl());
    }

    public List<CatalogInfo> listCatalogs(ConnectionProvider provider) {
        Set<String> names = new LinkedHashSet<String>();
        for (Schema schema : catalog(provider).getSchemas()) if (schema.getCatalogName() != null) names.add(schema.getCatalogName());
        List<CatalogInfo> out = new ArrayList<CatalogInfo>();
        for (String name : names) out.add(new CatalogInfo(name));
        return out;
    }

    public List<SchemaInfo> listSchemas(ConnectionProvider provider) {
        List<SchemaInfo> out = new ArrayList<SchemaInfo>();
        for (Schema schema : catalog(provider).getSchemas()) out.add(new SchemaInfo(schema.getCatalogName(), schema.getName()));
        return out;
    }

    public List<TableInfo> listTables(ConnectionProvider provider, MetadataQuery query) {
        Pattern pattern = Pattern.compile(query.getTablePattern().replace("%", ".*").replace("_", "."), Pattern.CASE_INSENSITIVE);
        List<TableInfo> out = new ArrayList<TableInfo>();
        for (Table table : catalog(provider).getTables()) {
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

    private Catalog catalog(ConnectionProvider provider) {
        try {
            schemacrawler.schemacrawler.SchemaCrawlerOptions options = SchemaCrawlerOptionsBuilder.newSchemaCrawlerOptions();
            return SchemaCrawlerUtility.getCatalog(connectionSource(provider), options);
        } catch (Exception e) {
            throw new SchemaLoomException("cannot read database metadata with SchemaCrawler", e);
        }
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
        List<FieldSchema> fields = new ArrayList<FieldSchema>();
        for (Column column : table.getColumns()) {
            Integer typeNumber = column.getColumnDataType().getJavaSqlType().getVendorTypeNumber();
            ColumnInfo info = new ColumnInfo(column.getName(), column.getColumnDataType().getDatabaseSpecificTypeName(), column.getRemarks(),
                    JdbcTypes.logical(typeNumber == null ? java.sql.Types.VARCHAR : typeNumber), column.getOrdinalPosition(), column.isNullable(),
                    column.getSize(), column.getSize(), column.getDecimalDigits(), column.getDefaultValue(), column.isAutoIncremented(), column.isGenerated());
            columns.add(info);
            fields.add(new FieldSchema(info.getName(), info.getLogicalType(), info.isNullable(), info.getLength(), info.getPrecision(), info.getScale()));
        }
        PrimaryKeyInfo primaryKey = primaryKey(table);
        List<String> keyColumns = primaryKey == null ? Collections.<String>emptyList() : primaryKey.getColumns();
        return new TableInfo(name, table.getTableType().isView(), table.getTableType().getTableType(), new RecordSchema(fields, keyColumns), columns,
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
