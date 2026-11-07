package io.github.nameof.schemaloom.dialect;

import cn.hutool.core.util.StrUtil;
import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.metadata.ColumnInfo;
import io.github.nameof.schemaloom.metadata.TableInfo;

import java.util.*;

final class SqlServerDialect extends AbstractDialect {
    private final java.util.Map<LogicalType, DatabaseTypeMapping> mappings = mappings();
    @Override
    protected String quoteChar() {
        return "\"";
    }

    /**
     * SQL Server 是三级别命名（库.模式.对象），且 T-SQL 原生支持「空模式段」写法 {@code 库..表}，
     * 表示「该库的默认模式」。只有库、没有模式时必须用它，否则 {@code 库.表} 会被当成
     * {@code schema.table} 而报「对象不存在」。
     */
    @Override
    protected boolean supportsEmptySchemaSegment() { return true; }

    @Override
    public DatabaseTypeMapping mapping(LogicalType type) { return mappings.get(type); }

    @Override
    protected String defaultValue(ColumnInfo column) {
        String value = column.getDefaultValue();
        if (value != null && value.trim().matches("(?i)GETDATE\\(\\)|GETUTCDATE\\(\\)|SYSDATETIME\\(\\)")) return "CURRENT_TIMESTAMP";
        if (value != null && value.trim().equalsIgnoreCase("TRUE")) return "1";
        if (value != null && value.trim().equalsIgnoreCase("FALSE")) return "0";
        return super.defaultValue(column);
    }

    @Override
    protected String identityColumn(ColumnInfo column) { return "IDENTITY(1,1)"; }

    @Override
    public String renameTableSql(String table, String newTable) {
        String source = normalizeRenameSource(table);
        String target = normalizeRenameTarget(newTable);
        return "EXEC sp_rename N'" + StrUtil.replace(source, "'", "''") + "', N'"
                + StrUtil.replace(target, "'", "''") + "'";
    }

    /**
     * sp_rename 的对象名最多接受「模式.对象」或「对象」，不接受带库的三段名，
     * 因此三段时丢掉库段；空模式段（{@code 库..表}）时只保留表名。
     */
    private String normalizeRenameSource(String table) {
        List<String> segments = segments(unquoteQualifiedName(table));
        if (segments.size() >= 3) segments = segments.subList(1, segments.size());
        return joinSkippingBlank(segments);
    }

    private String normalizeRenameTarget(String table) {
        return lastSegment(unquoteQualifiedName(table));
    }

    private String unquoteQualifiedName(String value) {
        return StrUtil.replace(StrUtil.replace(value, "\".\"", "."), "\"", "");
    }

    /** 按点号切分段并保留空段：{@code 库..表} 的空模式段本身就是有意义的语义。 */
    private List<String> segments(String value) {
        List<String> result = new ArrayList<String>();
        if (value == null) return result;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '.') {
                result.add(value.substring(start, i).trim());
                start = i + 1;
            }
        }
        result.add(value.substring(start).trim());
        return result;
    }

    private String joinSkippingBlank(List<String> segments) {
        StringBuilder joined = new StringBuilder();
        for (String segment : segments) {
            if (StrUtil.isBlank(segment)) continue;
            if (joined.length() > 0) joined.append('.');
            joined.append(segment);
        }
        return joined.toString();
    }

    /**
     * 扩展属性必须挂到实际写入的目标表对象上，因此架构名与表名都取自目标限定名，
     * 而不是源表元数据——REPLACE 模式下目标对象是 {@code <表>_tmp}。
     */
    @Override
    public List<String> commentSql(String table, TableInfo source) {
        List<String> sql = new ArrayList<String>();
        String target = unquoteQualifiedName(table);
        String tableName = lastSegment(target);
        if (StrUtil.isBlank(tableName)) tableName = source.getName().getTable();
        String schema = resolveSchema(target, source.getName());
        if (source.getRemarks() != null)
            sql.add("EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'" + literal(source.getRemarks())
                    + "', @level0type=N'SCHEMA', @level0name=N'" + literal(schema)
                    + "', @level1type=N'TABLE', @level1name=N'" + literal(tableName) + "'");
        for (ColumnInfo column : source.getColumns()) if (column.getRemarks() != null)
            sql.add("EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'" + literal(column.getRemarks())
                    + "', @level0type=N'SCHEMA', @level0name=N'" + literal(schema)
                    + "', @level1type=N'TABLE', @level1name=N'" + literal(tableName)
                    + "', @level2type=N'COLUMN', @level2name=N'" + literal(column.getName()) + "'");
        return sql;
    }

    /**
     * 目标限定名含架构段时取架构段；空架构段（{@code 库..表}）时回退源表架构，
     * 最后回退 SQL Server 默认架构 dbo —— {@code sp_addextendedproperty} 不接受空架构名。
     */
    private String resolveSchema(String target, QualifiedTableName source) {
        List<String> segments = segments(target);
        if (segments.size() >= 3 && StrUtil.isNotBlank(segments.get(1))) return segments.get(1);
        if (segments.size() == 2 && StrUtil.isNotBlank(segments.get(0))) return segments.get(0);
        String sourceSchema = source == null ? null : source.getSchema();
        return StrUtil.blankToDefault(sourceSchema, "dbo");
    }

    private String lastSegment(String target) {
        List<String> segments = segments(target);
        for (int i = segments.size() - 1; i >= 0; i--)
            if (StrUtil.isNotBlank(segments.get(i))) return segments.get(i);
        return null;
    }

    private String literal(String value) {
        return StrUtil.replace(value, "'", "''");
    }

    @Override
    protected String generatedColumn(ColumnInfo column) {
        String expression = column.getGeneratedExpression();
        if (expression == null || expression.trim().isEmpty())
            throw new IllegalArgumentException("generated column expression is missing for field '" + column.getName() + "'");
        String value = expression.trim();
        if (value.startsWith("(") && value.endsWith(")")) value = value.substring(1, value.length() - 1).trim();
        return "AS (" + value + ")";
    }

    @Override
    protected boolean generatedColumnIncludesType() { return false; }

    @Override
    public String identityInsertOnSql(String table) { return "SET IDENTITY_INSERT " + table + " ON"; }

    @Override
    public String identityInsertOffSql(String table) { return "SET IDENTITY_INSERT " + table + " OFF"; }

    /**
     * OBJECT_DEFINITION 返回完整的 CREATE VIEW 文本，需要截掉视图头只保留查询体。
     * CHARINDEX 找不到 ' AS ' 时返回 0，此时 SUBSTRING 会从第 4 个字符起截取，
     * 静默产出一段错位的视图定义；因此用 CASE 显式保护，取不到时返回 NULL，
     * 由调用方按“视图定义不可用”报错。UPPER 用于兼容区分大小写的排序规则。
     */
    @Override
    public ViewDefinitionQuery viewDefinitionQuery(DatabaseConnectionInfo source, QualifiedTableName view) {
        String name = view.getSchema() == null ? view.getTable() : view.getSchema() + "." + view.getTable();
        String sql = "SELECT CASE WHEN CHARINDEX(' AS ', UPPER(definition)) > 0"
                + " THEN SUBSTRING(definition, CHARINDEX(' AS ', UPPER(definition)) + 4, LEN(definition)) END"
                + " FROM (SELECT OBJECT_DEFINITION(OBJECT_ID(?)) AS definition) AS view_definition";
        return new ViewDefinitionQuery(sql, Collections.<Object>singletonList(name));
    }

    private java.util.Map<LogicalType, DatabaseTypeMapping> mappings() {
        java.util.EnumMap<LogicalType, DatabaseTypeMapping> mappings = new java.util.EnumMap<LogicalType, DatabaseTypeMapping>(LogicalType.class);
        // 方言矩阵与 DDL 类型保持同一份显式清单，新增逻辑类型必须同步评估。
        mappings.put(LogicalType.BOOLEAN, DatabaseTypeMapping.supported("BIT"));
        mappings.put(LogicalType.INT16, DatabaseTypeMapping.supported("SMALLINT"));
        mappings.put(LogicalType.INT32, DatabaseTypeMapping.supported("INT"));
        mappings.put(LogicalType.INT64, DatabaseTypeMapping.supported("BIGINT"));
        mappings.put(LogicalType.DECIMAL, DatabaseTypeMapping.supported(f ->
                "DECIMAL(" + Math.min(f.getPrecision() == null ? 38 : f.getPrecision(), 38) + ","
                        + Math.min(f.getScale() == null ? 0 : f.getScale(), 38) + ")"));
        mappings.put(LogicalType.FLOAT32, DatabaseTypeMapping.supported("REAL"));
        mappings.put(LogicalType.FLOAT64, DatabaseTypeMapping.supported("FLOAT"));
        mappings.put(LogicalType.STRING, DatabaseTypeMapping.supported(f -> {
            Integer length = f.getLength();
            return length != null && length <= 4000 ? "NVARCHAR(" + length + ")" : "NVARCHAR(MAX)";
        }));
        mappings.put(LogicalType.DATE, DatabaseTypeMapping.supported("DATE"));
        mappings.put(LogicalType.TIME, DatabaseTypeMapping.supported("TIME"));
        mappings.put(LogicalType.TIMESTAMP, DatabaseTypeMapping.supported("DATETIME2"));
        mappings.put(LogicalType.BINARY, DatabaseTypeMapping.supported(f ->
                f.getLength() != null && f.getLength() <= 8000
                        ? "VARBINARY(" + f.getLength() + ")" : "VARBINARY(MAX)"));
        mappings.put(LogicalType.OFFSET_TIME, DatabaseTypeMapping.degraded("TIME", LogicalType.TIME));
        mappings.put(LogicalType.OFFSET_TIMESTAMP, DatabaseTypeMapping.supported("DATETIMEOFFSET"));
        requireCompleteMappings(mappings);
        return java.util.Collections.unmodifiableMap(mappings);
    }
}
