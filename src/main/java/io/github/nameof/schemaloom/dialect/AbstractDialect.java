package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;

import java.util.*;

abstract class AbstractDialect implements DatabaseDialect {
    protected abstract String quoteChar();

    public String quote(String s) {
        if (s == null || s.trim().isEmpty()) throw new IllegalArgumentException("identifier is blank");
        String q = quoteChar();
        return q + s.replace(q, q + q) + q;
    }

    public String quote(QualifiedTableName table) {
        if (table == null) throw new IllegalArgumentException("table is required");
        StringBuilder sql = new StringBuilder();
        if (table.getCatalog() != null) sql.append(quote(table.getCatalog())).append('.');
        if (table.getSchema() != null) sql.append(quote(table.getSchema())).append('.');
        return sql.append(quote(table.getTable())).toString();
    }

    public String createTable(String table, RecordSchema s) {
        StringBuilder b = new StringBuilder("CREATE TABLE ").append(table).append(" (");
        for (int i = 0; i < s.getFields().size(); i++) {
            if (i > 0) b.append(", ");
            FieldSchema f = s.getFields().get(i);
            b.append(quote(f.getName())).append(' ').append(type(f));
            if (!f.isNullable()) b.append(" NOT NULL");
        }
        if (!s.getPrimaryKeyFields().isEmpty()) {
            b.append(", PRIMARY KEY (");
            for (int i = 0; i < s.getPrimaryKeyFields().size(); i++) {
                if (i > 0) b.append(", ");
                b.append(quote(s.getPrimaryKeyFields().get(i)));
            }
            b.append(')');
        }
        return b.append(')').toString();
    }

    public String dropTable(String table) {
        return "DROP TABLE " + table;
    }

    public String insert(String table, RecordSchema s) {
        StringBuilder b = new StringBuilder("INSERT INTO ").append(table).append(" (");
        for (int i = 0; i < s.getFields().size(); i++) {
            if (i > 0) b.append(", ");
            b.append(quote(s.getFields().get(i).getName()));
        }
        b.append(") VALUES (");
        for (int i = 0; i < s.getFields().size(); i++) {
            if (i > 0) b.append(", ");
            b.append('?');
        }
        return b.append(')').toString();
    }
}
