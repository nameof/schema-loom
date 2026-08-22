package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.metadata.ColumnInfo;
import io.github.nameof.schemaloom.metadata.TableInfo;

import java.util.*;

abstract class AbstractDialect implements DatabaseDialect {
    protected abstract String quoteChar();

    @Override
    public String quote(String s) {
        if (s == null || s.trim().isEmpty()) throw new IllegalArgumentException("identifier is blank");
        String q = quoteChar();
        return q + s.replace(q, q + q) + q;
    }

    @Override
    public String quote(QualifiedTableName table) {
        if (table == null) throw new IllegalArgumentException("table is required");
        StringBuilder sql = new StringBuilder();
        if (table.getCatalog() != null) sql.append(quote(table.getCatalog())).append('.');
        if (table.getSchema() != null) sql.append(quote(table.getSchema())).append('.');
        return sql.append(quote(table.getTable())).toString();
    }

    @Override
    public String createTableSql(String table, TableInfo source) {
        if (source == null) throw new IllegalArgumentException("source table metadata is required");
        Map<String, ColumnInfo> columns = new HashMap<>();
        for (ColumnInfo column : source.getColumns())
            columns.put(column.getName().toLowerCase(Locale.ENGLISH), column);
        return renderCreateTableSql(table, source.getSchema(), columns);
    }

    private String renderCreateTableSql(String table, RecordSchema s, Map<String, ColumnInfo> metadata) {
        StringBuilder b = new StringBuilder("CREATE TABLE ").append(table).append(" (");
        for (int i = 0; i < s.getFields().size(); i++) {
            if (i > 0) b.append(", ");
            FieldSchema f = s.getFields().get(i);
            DatabaseTypeMapping mapping = mapping(f.getLogicalType());
            if (mapping == null || !mapping.isSupported())
                throw new IllegalArgumentException("database does not support " + f.getLogicalType());
            b.append(quote(f.getName())).append(' ').append(mapping.getDdlType(f));
            if (metadata != null) {
                ColumnInfo column = metadata.get(f.getName().toLowerCase(Locale.ENGLISH));
                if (column != null) {
                    if (column.isGenerated()) b.append(' ').append(generatedColumn(column));
                    else if (column.isAutoIncremented()) b.append(' ').append(identityColumn(column));
                    else if (column.getDefaultValue() != null) b.append(" DEFAULT ").append(defaultValue(column));
                }
            }
            if (!f.isNullable() && (metadata == null || metadata.get(f.getName().toLowerCase(Locale.ENGLISH)) == null
                    || !metadata.get(f.getName().toLowerCase(Locale.ENGLISH)).isGenerated())) b.append(" NOT NULL");
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

    /** 目标方言的 identity / 自增列定义。 */
    protected String identityColumn(ColumnInfo column) {
        throw new IllegalArgumentException("identity columns are not supported by this dialect: " + column.getName());
    }

    /** 目标方言的生成列定义 */
    protected String generatedColumn(ColumnInfo column) {
        String expression = column.getGeneratedExpression();
        if (expression == null || expression.trim().isEmpty())
            throw new IllegalArgumentException("generated column expression is missing for field '" + column.getName() + "'");
        String value = expression.trim();
        if (!(value.startsWith("(") && value.endsWith(")"))) value = "(" + value + ")";
        return "GENERATED ALWAYS AS " + value;
    }

    /** 转换跨数据库可确认安全的默认值表达式；未知表达式必须显式失败。 */
    protected String defaultValue(ColumnInfo column) {
        String value = column.getDefaultValue();
        if (value == null) return null;
        String normalized = value.trim();
        while (normalized.length() >= 2 && normalized.charAt(0) == '(' && normalized.charAt(normalized.length() - 1) == ')')
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        // 部分 JDBC 驱动会去掉字符串默认值的引号；仅将无 SQL 标点的纯文本恢复为字符串字面量。
        if (column.getLogicalType() == LogicalType.STRING && normalized.matches("[\\p{L}\\p{N} _-]+"))
            return "'" + normalized.replace("'", "''") + "'";
        if (normalized.matches("[-+]?([0-9]+(\\.[0-9]+)?|\\.[0-9]+)") || normalized.matches("(?i)NULL|TRUE|FALSE"))
            return normalized.toUpperCase(Locale.ENGLISH).equals("NULL") ? "NULL" : normalized;
        if (normalized.matches("'(?:''|[^'])*'")) return normalized;
        if (normalized.matches("(?i)CURRENT_DATE|CURRENT_TIME|CURRENT_TIMESTAMP")) return normalized.toUpperCase(Locale.ENGLISH);
        throw new IllegalArgumentException("cannot safely migrate default value for field '" + column.getName() + "': " + value);
    }

    protected final void requireCompleteMappings(Map<LogicalType, DatabaseTypeMapping> mappings) {
        // 方言必须显式声明每个逻辑类型，即使某类型不支持也要放入 unsupported()。
        if (mappings.size() != LogicalType.values().length)
            throw new IllegalStateException("database logical type mapping is incomplete");
    }

    @Override
    public String dropTable(String table) {
        return "DROP TABLE " + table;
    }

    @Override
    public String createView(String view, String definition) {
        if (definition == null || definition.trim().isEmpty()) throw new IllegalArgumentException("view definition is required");
        return "CREATE VIEW " + view + " AS " + definition;
    }

    @Override
    public String dropView(String view) {
        return "DROP VIEW " + view;
    }

    @Override
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
