package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.source.JdbcTypes;

import java.sql.*;
import java.util.*;

public final class DatabaseMetadataService {
    public List<String> listCatalogs(ConnectionProvider p) {
        try {
            List<String> out = new ArrayList<String>();
            ResultSet r = p.getConnection().getMetaData().getCatalogs();
            try {
                while (r.next()) out.add(r.getString(1));
            } finally {
                r.close();
            }
            return out;
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot read catalogs", e);
        }
    }

    public List<String> listSchemas(ConnectionProvider p) {
        try {
            List<String> out = new ArrayList<String>();
            ResultSet r = p.getConnection().getMetaData().getSchemas();
            try {
                while (r.next()) out.add(r.getString("TABLE_SCHEM"));
            } finally {
                r.close();
            }
            return out;
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot read schemas", e);
        }
    }

    public List<TableInfo> listTables(ConnectionProvider p, MetadataQuery q) {
        try {
            List<TableInfo> out = new ArrayList<TableInfo>();
            DatabaseMetaData m = p.getConnection().getMetaData();
            ResultSet r = m.getTables(q.getCatalog(), q.getSchema(), q.getTablePattern(), new String[]{"TABLE", "VIEW"});
            try {
                while (r.next()) {
                    String name = r.getString("TABLE_NAME");
                    out.add(new TableInfo(new QualifiedTableName(r.getString("TABLE_CAT"), r.getString("TABLE_SCHEM"), name), "VIEW".equalsIgnoreCase(r.getString("TABLE_TYPE")), columns(m, q.getCatalog(), q.getSchema(), name)));
                }
            } finally {
                r.close();
            }
            return out;
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot read tables", e);
        }
    }

    public TableInfo getTable(ConnectionProvider p, QualifiedTableName n) {
        for (TableInfo t : listTables(p, new MetadataQuery(n.getCatalog(), n.getSchema(), n.getTable())))
            if (t.getName().getTable().equalsIgnoreCase(n.getTable())) return t;
        throw new SchemaLoomException("table not found: " + n.getTable());
    }

    private RecordSchema columns(DatabaseMetaData m, String c, String s, String t) throws SQLException {
        List<FieldSchema> fs = new ArrayList<FieldSchema>();
        ResultSet r = m.getColumns(c, s, t, "%");
        try {
            while (r.next())
                fs.add(new FieldSchema(r.getString("COLUMN_NAME"), JdbcTypes.logical(r.getInt("DATA_TYPE")), r.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, r.getInt("COLUMN_SIZE"), r.getInt("DECIMAL_DIGITS"), r.getInt("DECIMAL_DIGITS")));
        } finally {
            r.close();
        }
        return new RecordSchema(fs);
    }
}
