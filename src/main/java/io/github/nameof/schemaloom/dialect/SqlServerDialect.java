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

    private String normalizeRenameSource(String table) {
        String value = unquoteQualifiedName(table);
        int firstDot = value.indexOf('.');
        if (firstDot >= 0 && value.indexOf('.', firstDot + 1) >= 0) value = value.substring(firstDot + 1);
        return value;
    }

    private String normalizeRenameTarget(String table) {
        String value = unquoteQualifiedName(table);
        int lastDot = value.lastIndexOf('.');
        return lastDot < 0 ? value : value.substring(lastDot + 1);
    }

    private String unquoteQualifiedName(String value) {
        return StrUtil.replace(StrUtil.replace(value, "\".\"", "."), "\"", "");
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

    /** 目标限定名含架构段时取架构段；否则回退源表架构，最后回退 SQL Server 默认架构 dbo。 */
    private String resolveSchema(String target, QualifiedTableName source) {
        List<String> segments = StrUtil.split(target, '.');
        if (segments.size() >= 3) return segments.get(1);
        if (segments.size() == 2) return segments.get(0);
        return source.getSchema() == null ? "dbo" : source.getSchema();
    }

    private String lastSegment(String target) {
        List<String> segments = StrUtil.split(target, '.');
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
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
        mappings.put(LogicalType.OFFSET_TIME, DatabaseTypeMapping.supported("TIME"));
        mappings.put(LogicalType.OFFSET_TIMESTAMP, DatabaseTypeMapping.supported("DATETIMEOFFSET"));
        requireCompleteMappings(mappings);
        return java.util.Collections.unmodifiableMap(mappings);
    }
}
