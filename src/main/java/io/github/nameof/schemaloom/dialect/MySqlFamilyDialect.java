package io.github.nameof.schemaloom.dialect;

import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.metadata.ColumnInfo;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.metadata.TableInfo;

import java.util.*;

/**
 * MySQL 家族方言（MySQL / MariaDB / GBase 8a）的公共基类。
 *
 * <p>家族共性集中在此，避免同一规则在三份方言里各写一遍（改一处漏两处的典型现场：
 * 注释转义与视图查询曾各复制三份）：反引号引用符、{@code AUTO_INCREMENT} 自增列、
 * {@code NOW()}/{@code CURRENT_TIMESTAMP()} 默认值规范化、仅迁移表注释、
 * 从 {@code INFORMATION_SCHEMA.VIEWS} 读取视图定义、{@code RENAME TO} 保留完整新名。</p>
 *
 * <p>子类只覆盖差异点：{@link #decimalPrecisionCap()}、{@link #booleanMapping()}、
 * {@link #stringDdlType(FieldSchema)}，以及生成列、identity 插入开关等能力。
 * <b>注意</b>：这些钩子会在构造期被 {@link #buildMappings()} 调用，实现必须是常量返回，
 * 不得依赖子类自己的字段。</p>
 */
abstract class MySqlFamilyDialect extends AbstractDialect {

    /** 类型映射在构造期一次性完成并冻结，避免每次 {@link #mapping(LogicalType)} 重复构建。 */
    private final Map<LogicalType, DatabaseTypeMapping> mappings = freezeMappings(buildMappings());

    @Override
    protected String quoteChar() { return "`"; }

    @Override
    public final DatabaseTypeMapping mapping(LogicalType type) { return mappings.get(type); }

    /** MySQL 家族把 {@code NOW()}/{@code CURRENT_TIMESTAMP()} 统一规范化为 {@code CURRENT_TIMESTAMP}。 */
    @Override
    protected String defaultValue(ColumnInfo column) {
        String value = column.getDefaultValue();
        if (value != null && ReUtil.isMatch("(?i)NOW\\(\\)|CURRENT_TIMESTAMP\\(\\)", StrUtil.trim(value)))
            return "CURRENT_TIMESTAMP";
        return super.defaultValue(column);
    }

    @Override
    protected String identityColumn(ColumnInfo column) { return "AUTO_INCREMENT"; }

    /**
     * MySQL 家族的 {@code RENAME TO} 允许新名带限定名并视作跨库搬家，
     * 这是本实现有意不提供的语义，因此保留调用方传入的完整新名，
     * 而不是像 {@link AbstractDialect#renameTableSql} 那样只取最后一段标识符。
     */
    @Override
    public String renameTableSql(String table, String newTable) {
        return "ALTER TABLE " + table + " RENAME TO " + newTable;
    }

    /**
     * 仅迁移表注释。MySQL 家族没有「无损修改单列注释」的独立语法
     * （{@code MODIFY COLUMN} 要求完整列定义，容易丢列属性），因此字段注释暂不迁移。
     */
    @Override
    public List<String> commentSql(String table, TableInfo source) {
        List<String> sql = new ArrayList<>();
        if (source.getRemarks() != null)
            sql.add("ALTER TABLE " + table + " COMMENT = '" + commentLiteral(source.getRemarks()) + "'");
        return sql;
    }

    /** MySQL 家族的字符串里 {@code \} 是转义符，必须先转义反斜杠再转义单引号。 */
    protected final String commentLiteral(String value) {
        return StrUtil.replace(StrUtil.replace(value, "\\", "\\\\"), "'", "''");
    }

    /** MySQL 家族的视图定义统一从 {@code INFORMATION_SCHEMA.VIEWS} 按「库 + 视图名」读取。 */
    @Override
    public ViewDefinitionQuery viewDefinitionQuery(DatabaseConnectionInfo source, QualifiedTableName view) {
        String schema = view.getCatalog() == null ? source.getCatalog() : view.getCatalog();
        if (schema == null) schema = source.getDatabase();
        return new ViewDefinitionQuery("SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", Arrays.<Object>asList(schema, view.getTable()));
    }

    /** {@code DECIMAL} 精度上限：MySQL / MariaDB 为 65，GBase 8a 为 38（子类覆盖）。 */
    protected int decimalPrecisionCap() { return 65; }

    /** {@code BOOLEAN} 映射：MySQL / MariaDB 原生支持；GBase 8a 无该关键字，覆盖为 {@code TINYINT}。 */
    protected DatabaseTypeMapping booleanMapping() { return DatabaseTypeMapping.supported("BOOLEAN"); }

    /** 字符串类型分档：{@code VARCHAR(≤255)} → {@code TEXT(≤65535)} → {@code LONGTEXT}。 */
    protected String stringDdlType(FieldSchema field) {
        Integer length = field.getLength();
        return length != null && length <= 255 ? "VARCHAR(" + length + ")"
                : length != null && length <= 65535 ? "TEXT" : "LONGTEXT";
    }

    /**
     * 家族默认类型映射（MySQL / MariaDB 完全一致）。子类只需覆盖与家族默认不同的钩子，
     * 不必整份重写映射表。
     */
    protected Map<LogicalType, DatabaseTypeMapping> buildMappings() {
        Map<LogicalType, DatabaseTypeMapping> mappings = new EnumMap<>(LogicalType.class);
        // 这里必须逐项登记；不要用遍历枚举加默认 supported，避免新增类型被错误放行。
        int precisionCap = decimalPrecisionCap();
        mappings.put(LogicalType.BOOLEAN, booleanMapping());
        mappings.put(LogicalType.INT16, DatabaseTypeMapping.supported("SMALLINT"));
        mappings.put(LogicalType.INT32, DatabaseTypeMapping.supported("INT"));
        mappings.put(LogicalType.INT64, DatabaseTypeMapping.supported("BIGINT"));
        mappings.put(LogicalType.DECIMAL, DatabaseTypeMapping.supported(f ->
                "DECIMAL(" + Math.min(f.getPrecision() == null ? precisionCap : f.getPrecision(), precisionCap) + ","
                        + Math.min(f.getScale() == null ? 0 : f.getScale(), 30) + ")"));
        mappings.put(LogicalType.FLOAT32, DatabaseTypeMapping.supported("FLOAT"));
        mappings.put(LogicalType.FLOAT64, DatabaseTypeMapping.supported("DOUBLE"));
        mappings.put(LogicalType.STRING, DatabaseTypeMapping.supported(this::stringDdlType));
        mappings.put(LogicalType.DATE, DatabaseTypeMapping.supported("DATE"));
        mappings.put(LogicalType.TIME, DatabaseTypeMapping.supported("TIME"));
        mappings.put(LogicalType.TIMESTAMP, DatabaseTypeMapping.supported("DATETIME"));
        mappings.put(LogicalType.BINARY, DatabaseTypeMapping.supported("BLOB"));
        mappings.put(LogicalType.OFFSET_TIME, DatabaseTypeMapping.degraded("TIME", LogicalType.TIME));
        mappings.put(LogicalType.OFFSET_TIMESTAMP, DatabaseTypeMapping.degraded("DATETIME", LogicalType.TIMESTAMP));
        return mappings;
    }

    /** 校验映射完整并冻结，防止子类或调用方事后改动。 */
    private Map<LogicalType, DatabaseTypeMapping> freezeMappings(Map<LogicalType, DatabaseTypeMapping> mappings) {
        requireCompleteMappings(mappings);
        return Collections.unmodifiableMap(mappings);
    }
}
