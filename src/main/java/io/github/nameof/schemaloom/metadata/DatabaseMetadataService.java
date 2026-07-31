package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.source.JdbcTypes;

import java.sql.*;
import java.util.*;

public final class DatabaseMetadataService {
    public DatabaseInfo getDatabaseInfo(ConnectionProvider provider) {
        try {
            DatabaseMetaData m = provider.getConnection().getMetaData();
            return new DatabaseInfo(m.getDatabaseProductName(), m.getDatabaseProductVersion(), m.getDriverName(), m.getDriverVersion(), m.getURL());
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot read database information", e);
        }
    }

    public List<CatalogInfo> listCatalogs(ConnectionProvider provider) {
        try {
            List<CatalogInfo> out = new ArrayList<CatalogInfo>();
            ResultSet r = provider.getConnection().getMetaData().getCatalogs();
            try {
                while (r.next()) out.add(new CatalogInfo(r.getString("TABLE_CAT")));
            } finally {
                r.close();
            }
            return out;
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot read catalogs", e);
        }
    }

    public List<SchemaInfo> listSchemas(ConnectionProvider provider) {
        try {
            List<SchemaInfo> out = new ArrayList<SchemaInfo>();
            ResultSet r = provider.getConnection().getMetaData().getSchemas();
            try {
                while (r.next()) out.add(new SchemaInfo(r.getString("TABLE_CATALOG"), r.getString("TABLE_SCHEM")));
            } finally {
                r.close();
            }
            return out;
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot read schemas", e);
        }
    }

    public List<TableInfo> listTables(ConnectionProvider provider, MetadataQuery query) {
        try {
            List<TableInfo> out = new ArrayList<TableInfo>();
            DatabaseMetaData m = provider.getConnection().getMetaData();
            ResultSet r = m.getTables(query.getCatalog(), query.getSchema(), query.getTablePattern(), new String[]{"TABLE", "VIEW"});
            try {
                while (r.next()) {
                    String catalog = r.getString("TABLE_CAT"), schema = r.getString("TABLE_SCHEM"), name = r.getString("TABLE_NAME");
                    out.add(readTable(m, new QualifiedTableName(catalog, schema, name), "VIEW".equalsIgnoreCase(r.getString("TABLE_TYPE")), r.getString("REMARKS")));
                }
            } finally {
                r.close();
            }
            return out;
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot read tables", e);
        }
    }

    public TableInfo getTable(ConnectionProvider provider, QualifiedTableName name) {
        for (TableInfo table : listTables(provider, new MetadataQuery(name.getCatalog(), name.getSchema(), name.getTable())))
            if (table.getName().getTable().equalsIgnoreCase(name.getTable())) return table;
        throw new SchemaLoomException("table not found: " + name.getTable());
    }

    private TableInfo readTable(DatabaseMetaData m, QualifiedTableName name, boolean view, String remarks) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        List<FieldSchema> fields = new ArrayList<FieldSchema>();
        ResultSet c = m.getColumns(name.getCatalog(), name.getSchema(), name.getTable(), "%");
        try {
            while (c.next()) {
                int type = c.getInt("DATA_TYPE"), size = c.getInt("COLUMN_SIZE"), scale = c.getInt("DECIMAL_DIGITS");
                Integer nullable = c.getInt("NULLABLE");
                ColumnInfo info = new ColumnInfo(c.getString("COLUMN_NAME"), c.getString("TYPE_NAME"), c.getString("REMARKS"), JdbcTypes.logical(type), c.getInt("ORDINAL_POSITION"), nullable != DatabaseMetaData.columnNoNulls, size, size, scale);
                columns.add(info);
                fields.add(new FieldSchema(info.getName(), info.getLogicalType(), info.isNullable(), info.getLength(), info.getPrecision(), info.getScale()));
            }
        } finally {
            c.close();
        }
        PrimaryKeyInfo pk = readPrimaryKey(m, name);
        List<String> keyColumns = pk == null ? Collections.<String>emptyList() : pk.getColumns();
        List<IndexInfo> indexes = readIndexes(m, name);
        return new TableInfo(name, view, new RecordSchema(fields, keyColumns), columns, pk, indexes, remarks);
    }

    private PrimaryKeyInfo readPrimaryKey(DatabaseMetaData m, QualifiedTableName name) throws SQLException {
        TreeMap<Short, String> columns = new TreeMap<Short, String>();
        String pkName = null;
        ResultSet r = m.getPrimaryKeys(name.getCatalog(), name.getSchema(), name.getTable());
        try {
            while (r.next()) {
                if (pkName == null) pkName = r.getString("PK_NAME");
                columns.put(r.getShort("KEY_SEQ"), r.getString("COLUMN_NAME"));
            }
        } finally {
            r.close();
        }
        return columns.isEmpty() ? null : new PrimaryKeyInfo(pkName, new ArrayList<String>(columns.values()));
    }

    private List<IndexInfo> readIndexes(DatabaseMetaData m, QualifiedTableName name) throws SQLException {
        Map<String, List<String>> columns = new LinkedHashMap<String, List<String>>();
        Map<String, Boolean> unique = new HashMap<String, Boolean>();
        ResultSet r = m.getIndexInfo(name.getCatalog(), name.getSchema(), name.getTable(), false, false);
        try {
            while (r.next()) {
                String index = r.getString("INDEX_NAME"), column = r.getString("COLUMN_NAME");
                if (index == null || column == null) continue;
                if (!columns.containsKey(index)) columns.put(index, new ArrayList<String>());
                columns.get(index).add(column);
                unique.put(index, !r.getBoolean("NON_UNIQUE"));
            }
        } finally {
            r.close();
        }
        List<IndexInfo> out = new ArrayList<IndexInfo>();
        for (Map.Entry<String, List<String>> e : columns.entrySet())
            out.add(new IndexInfo(e.getKey(), Boolean.TRUE.equals(unique.get(e.getKey())), e.getValue()));
        return out;
    }
}
