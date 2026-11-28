package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.metadata.ColumnInfo;
import io.github.nameof.schemaloom.metadata.TableInfo;
import io.github.nameof.schemaloom.metadata.IndexInfo;

import java.util.*;

abstract class AbstractDialect implements DatabaseDialect {
    protected abstract String quoteChar();

    @Override
    public String quote(String s) {
        if (s == null || s.trim().isEmpty()) throw new IllegalArgumentException("identifier is blank");
        String q = quoteChar();
        return q + s.replace(q, q + q) + q;
    }

    /**
     * 生成带标识符引用的限定表名。
     * <p>
     * catalog 与 schema 是两个不同层级：MySQL / MariaDB / GBase 8a 只有「库即 schema」一层，
     * 传 catalog 时输出两段名是正确的；SQL Server / DB2 这类三级命名的库，两段名会被解析成
     * {@code schema.table}，只给 catalog 时拼出的 {@code 库.表} 反而指向一个不存在的 schema
     * （SQL Server 实测报 {@code Invalid object name}）。这类方言用「空模式段」表达
     * 「库 + 默认 schema」，见 {@link #supportsEmptySchemaSegment()}。
     * </p>
     * <p>
     * 此外，quote不负责表名大小写的转换，不同数据库对大小写的行为不一致，由{@code DatabaseConnectionInfo.table}构造的QualifiedTableName统一处理
     * </p>
     */
    @Override
    public String quote(QualifiedTableName table) {
        if (table == null) throw new IllegalArgumentException("table is required");
        StringBuilder sql = new StringBuilder();
        if (table.getCatalog() != null) sql.append(quote(table.getCatalog())).append('.');
        if (table.getSchema() != null) sql.append(quote(table.getSchema())).append('.');
        else if (table.getCatalog() != null && supportsEmptySchemaSegment()) sql.append('.');
        return sql.append(quote(table.getTable())).toString();
    }

    /**
     * 三级别命名（库.模式.对象）的方言是否接受空模式段，即 {@code 库..表} 的写法。
     * <p>默认不接受：多数方言只用两段名表达「schema.table」，让 catalog 独占一段会改变语义。</p>
     */
    protected boolean supportsEmptySchemaSegment() {
        return false;
    }

    @Override
    public String createTableSql(String table, TableInfo sourceTable) {
        if (sourceTable == null) throw new IllegalArgumentException("source table metadata is required");
        Map<String, ColumnInfo> columns = new HashMap<>();
        for (ColumnInfo column : sourceTable.getColumns())
            columns.put(column.getName().toLowerCase(Locale.ENGLISH), column);
        List<String> primaryKey = sourceTable.getPrimaryKey() == null ? Collections.emptyList() : sourceTable.getPrimaryKey().getColumns();
        return renderCreateTableSql(table, sourceTable.toRecordSchema(), columns, primaryKey);
    }

    @Override
    public String createTableSql(String table, RecordSchema source) {
        if (source == null) throw new IllegalArgumentException("record schema is required");
        return renderCreateTableSql(table, source, null, Collections.emptyList());
    }

    /**
     * Shared by Oracle, PostgreSQL, H2 and other databases supporting
     * the standard COMMENT ON TABLE/COLUMN syntax.
     */    @Override
    public List<String> commentSql(String table, TableInfo source) {
        List<String> sql = new ArrayList<>();
        if (source.getRemarks() != null) sql.add("COMMENT ON TABLE " + table + " IS '" + source.getRemarks().replace("'", "''") + "'");
        for (ColumnInfo column : source.getColumns())
            if (column.getRemarks() != null)
                sql.add("COMMENT ON COLUMN " + table + "." + quote(column.getName()) + " IS '" + column.getRemarks().replace("'", "''") + "'");
        return sql;
    }

    /**
     * 主键索引的剔除统一在 {@code DatabaseMetadataService#indexes} 完成（含保留名与列集合两项判据），
     * 方言层不再重复过滤，避免同一规则在两处以不同判据各实现一遍。
     */
    @Override
    public List<String> indexSql(String table, List<IndexInfo> indexes) {
        return standardIndexSql(table, indexes);
    }

    /** 支持普通的 B-tree/唯一索引语法。 */
    private List<String> standardIndexSql(String table, List<IndexInfo> indexes) {
        List<String> sql = new ArrayList<>();
        for (IndexInfo index : indexes) {
            if (index.getName() == null || index.getName().trim().isEmpty() || index.getColumns().isEmpty()) continue;
            StringBuilder statement = new StringBuilder("CREATE ");
            if (index.isUnique()) statement.append("UNIQUE ");
            statement.append("INDEX ").append(quote(index.getName())).append(" ON ").append(table).append(" (");
            for (int i = 0; i < index.getColumns().size(); i++) {
                if (i > 0) statement.append(", ");
                statement.append(quote(index.getColumns().get(i)));
            }
            sql.add(statement.append(')').toString());
        }
        return sql;
    }


    private String renderCreateTableSql(String table, RecordSchema s, Map<String, ColumnInfo> metadata,
                                        List<String> primaryKeyFields) {
        StringBuilder b = new StringBuilder("CREATE TABLE ").append(table).append(" (");
        for (int i = 0; i < s.getFields().size(); i++) {
            if (i > 0) b.append(", ");
            FieldSchema f = s.getFields().get(i);
            DatabaseTypeMapping mapping = mapping(f.getLogicalType());
            if (mapping == null || !mapping.isSupported())
                throw new IllegalArgumentException("database does not support " + f.getLogicalType());
            if (metadata != null) {
                ColumnInfo column = metadata.get(f.getName().toLowerCase(Locale.ENGLISH));
                b.append(quote(f.getName())).append(' ');
                if (column != null && column.isGenerated() && !generatedColumnIncludesType()) b.append(generatedColumn(column));
                else {
                    b.append(mapping.getDdlType(f));
                    if (column != null) {
                        if (column.isGenerated()) b.append(' ').append(generatedColumn(column));
                        else if (column.isAutoIncremented()) b.append(' ').append(identityColumn(column));
                        else if (column.getDefaultValue() != null) b.append(" DEFAULT ").append(defaultValue(column));
                    }
                }
            } else {
                b.append(quote(f.getName())).append(' ').append(mapping.getDdlType(f));
            }
            if (!f.isNullable() && (metadata == null || metadata.get(f.getName().toLowerCase(Locale.ENGLISH)) == null
                    || !metadata.get(f.getName().toLowerCase(Locale.ENGLISH)).isGenerated())) b.append(" NOT NULL");
        }
        if (!primaryKeyFields.isEmpty()) {
            b.append(", PRIMARY KEY (");
            for (int i = 0; i < primaryKeyFields.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(quote(primaryKeyFields.get(i)));
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
        // DB2 等数据库返回的表达式可能包含前缀 "AS "，需要去除以避免生成重复的 "AS AS ..."
        if (value.toUpperCase(Locale.ENGLISH).startsWith("AS ")) value = value.substring(3).trim();
        if (!(value.startsWith("(") && value.endsWith(")"))) value = "(" + value + ")";
        return "GENERATED ALWAYS AS " + value;
    }

    /** 某些方言在计算列表达式前不允许声明列类型。 */
    protected boolean generatedColumnIncludesType() { return true; }

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
    public String dropTableSql(String table) {
        return "DROP TABLE " + table;
    }

    /**
     * <p>改表名传入全限定名 {@code "SCHEMA"."TABLE"} 会被几种数据库语法拒绝，因为语义是不能更改表的归属，只能改表名
     * （Oracle 报 ORA-14047，26ai 实测）。</p>
     *
     * <p><b>Oracle、PostgreSQL、Kingbase、达梦直接用本实现</b>（语法均为 {@code ALTER TABLE ... RENAME TO}，需提取裸表名）</p>
     *
     * <p><b>必须自行实现</b>：</p>
     * <ul>
     *   <li>DB2 —— 语法是 {@code RENAME TABLE t TO n}，不是 {@code ALTER TABLE ... RENAME TO}；</li>
     *   <li>SQL Server —— 走 {@code EXEC sp_rename}，且对象名不接受三段名，需自行归一；</li>
     *   <li>MySQL / MariaDB / GBase 8a —— {@code RENAME TO} 允许新名带限定名并视作跨库搬家，
     *       这是本实现有意不提供的语义，故各自保留完整新名。</li>
     * </ul>
     */
    @Override
    public String renameTableSql(String table, String newTable) {
        return "ALTER TABLE " + table + " RENAME TO " + lastIdentifier(newTable);
    }

    /**
     * 从限定名中取出最后一段标识符，跳过引号内的点号与转义引号。
     *
     * <p>例如 {@code "SL_TARGET"."USER_INFO"} → {@code "USER_INFO"}；
     * {@code "a.b"."c"} → {@code "c"}，而不会被表名中的点号切错。</p>
     */
    protected String lastIdentifier(String qualified) {
        boolean quoted = false;
        int separator = -1;
        char quote = quoteChar().charAt(0);
        for (int i = 0; i < qualified.length(); i++) {
            char c = qualified.charAt(i);
            if (c == quote) {
                if (quoted && i + 1 < qualified.length() && qualified.charAt(i + 1) == quote) { i++; continue; }
                quoted = !quoted;
            } else if (c == '.' && !quoted) separator = i;
        }
        return separator < 0 ? qualified : qualified.substring(separator + 1);
    }

    @Override
    public String createViewSql(String view, String definition) {
        if (definition == null || definition.trim().isEmpty()) throw new IllegalArgumentException("view definition is required");
        return "CREATE VIEW " + view + " AS " + definition;
    }

    @Override
    public String dropViewSql(String view) {
        return "DROP VIEW " + view;
    }

    @Override
    public String insertSql(String table, RecordSchema s) {
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

    @Override
    public String identityInsertOnSql(String table) { return null; }

    @Override
    public String identityInsertOffSql(String table) { return null; }
}
