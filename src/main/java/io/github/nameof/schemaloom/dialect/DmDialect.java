package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.metadata.ColumnInfo;
import io.github.nameof.schemaloom.metadata.IndexInfo;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;

import java.util.*;

final class DmDialect extends AbstractDialect {
    private final Map<LogicalType, DatabaseTypeMapping> mappings = mappings();

    @Override protected String quoteChar() { return "\""; }
    // 达梦未加引号的标识符存储为大写（与 Oracle 兼容行为），统一大写后加引号。
    @Override public String quote(String s) { return super.quote(s.toUpperCase(Locale.ENGLISH)); }
    @Override public DatabaseTypeMapping mapping(LogicalType type) { return mappings.get(type); }
    @Override protected String identityColumn(ColumnInfo column) { return "IDENTITY(1,1)"; }

    @Override
    public String identityInsertOnSql(String table) { return "SET IDENTITY_INSERT " + table + " ON"; }

    @Override
    public String identityInsertOffSql(String table) { return "SET IDENTITY_INSERT " + table + " OFF"; }

    // 达梦的 rename 输入可能包含已引用的小写标识符，需要大写后重建。
    // 达梦的 RENAME TO 子句只接受表名，不接受 schema 限定名。
    @Override
    public String renameTableSql(String table, String newTable) {
        return "ALTER TABLE " + uppercaseQuoted(table) + " RENAME TO " + uppercaseQuoted(extractTableName(newTable));
    }

    private String extractTableName(String qualifiedName) {
        // 从 "SCHEMA"."TABLE" 或 SCHEMA.TABLE 中提取最后的表名部分
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
    }

    private String uppercaseQuoted(String identifier) {
        if (identifier.startsWith("\"") && identifier.endsWith("\""))
            return "\"" + identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"").toUpperCase(Locale.ENGLISH) + "\"";
        return identifier;
    }

    @Override
    protected String defaultValue(ColumnInfo column) {
        String value = column.getDefaultValue();
        if (value != null) {
            String trimmed = value.trim();
            if (trimmed.equalsIgnoreCase("SYSDATE")) return "CURRENT_TIMESTAMP";
            if (trimmed.equalsIgnoreCase("SYSTIMESTAMP")) return "CURRENT_TIMESTAMP";
        }
        return super.defaultValue(column);
    }

    @Override
    public ViewDefinitionQuery viewDefinitionQuery(DatabaseConnectionInfo source, QualifiedTableName view) {
        String owner = view.getSchema() == null ? source.getSchema() : view.getSchema();
        if (owner == null) throw new IllegalArgumentException("达梦视图迁移需要指定 schema");
        return new ViewDefinitionQuery("SELECT TEXT FROM ALL_VIEWS WHERE OWNER = ? AND VIEW_NAME = ?",
                Arrays.<Object>asList(owner.toUpperCase(Locale.ENGLISH), view.getTable().toUpperCase(Locale.ENGLISH)));
    }

    // ALL_VIEWS.TEXT 包含完整的 CREATE VIEW 语句，需要提取 AS 之后的 SELECT 部分
    @Override
    public String createViewSql(String view, String definition) {
        if (definition == null || definition.trim().isEmpty()) throw new IllegalArgumentException("view definition is required");
        String def = definition.trim();
        String upper = def.toUpperCase(Locale.ENGLISH);
        if (upper.startsWith("CREATE ")) {
            int asIndex = upper.indexOf(" AS ");
            if (asIndex > 0) def = def.substring(asIndex + 4).trim();
        }
        return "CREATE VIEW " + view + " AS " + def;
    }

    // 达梦的索引名称是 Schema 级别的（与 Oracle 类似），同一 Schema 下不同表不能有同名索引。
    // 迁移时为索引添加 "_TGT" 后缀以避免与源表索引冲突。
    @Override
    public List<String> indexSql(String table, List<IndexInfo> indexes) {
        List<String> sql = new ArrayList<String>();
        for (IndexInfo index : indexes) {
            if (index.getName() == null || index.getName().trim().isEmpty() || index.getColumns().isEmpty()) continue;
            StringBuilder statement = new StringBuilder("CREATE ");
            if (index.isUnique()) statement.append("UNIQUE ");
            // 为索引名称添加 _TGT 后缀（在加引号之前）
            String indexName = quote(index.getName() + "_TGT");
            statement.append("INDEX ").append(indexName).append(" ON ").append(table).append(" (");
            for (int i = 0; i < index.getColumns().size(); i++) {
                if (i > 0) statement.append(", ");
                statement.append(quote(index.getColumns().get(i)));
            }
            sql.add(statement.append(')').toString());
        }
        return sql;
    }

    private Map<LogicalType, DatabaseTypeMapping> mappings() {
        EnumMap<LogicalType, DatabaseTypeMapping> m = new EnumMap<LogicalType, DatabaseTypeMapping>(LogicalType.class);
        m.put(LogicalType.BOOLEAN, DatabaseTypeMapping.supported("BIT"));
        m.put(LogicalType.INT16, DatabaseTypeMapping.supported("SMALLINT"));
        m.put(LogicalType.INT32, DatabaseTypeMapping.supported("INT"));
        m.put(LogicalType.INT64, DatabaseTypeMapping.supported("BIGINT"));
        m.put(LogicalType.DECIMAL, DatabaseTypeMapping.supported(f ->
                "DECIMAL(" + Math.min(f.getPrecision() == null ? 38 : f.getPrecision(), 38) + ","
                        + Math.min(f.getScale() == null ? 0 : f.getScale(), 38) + ")"));
        m.put(LogicalType.FLOAT32, DatabaseTypeMapping.supported("REAL"));
        m.put(LogicalType.FLOAT64, DatabaseTypeMapping.supported("DOUBLE"));
        m.put(LogicalType.STRING, DatabaseTypeMapping.supported(f ->
                f.getLength() != null && f.getLength() > 0 ? "VARCHAR(" + f.getLength() + ")" : "VARCHAR(32767)"));
        m.put(LogicalType.DATE, DatabaseTypeMapping.supported("DATE"));
        m.put(LogicalType.TIME, DatabaseTypeMapping.supported("TIME"));
        m.put(LogicalType.TIMESTAMP, DatabaseTypeMapping.supported("TIMESTAMP"));
        m.put(LogicalType.BINARY, DatabaseTypeMapping.supported("BLOB"));
        m.put(LogicalType.OFFSET_TIME, DatabaseTypeMapping.supported("TIME WITH TIME ZONE"));
        m.put(LogicalType.OFFSET_TIMESTAMP, DatabaseTypeMapping.supported("TIMESTAMP WITH TIME ZONE"));
        requireCompleteMappings(m);
        return Collections.unmodifiableMap(m);
    }
}
