package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.FieldSchema;
import io.github.nameof.schemaloom.metadata.ColumnInfo;

/**
 * GBase 8a 是 OLAP 分析型数据库，SQL 方言高度兼容 MySQL，
 * 因此复用 {@link MySqlFamilyDialect} 的家族实现，仅覆盖以下差异：
 * <ul>
 *   <li>{@code DECIMAL} 精度上限为 38（MySQL 为 65）；</li>
 *   <li>不支持 {@code LONGTEXT} 类型，超长字符串统一落到 {@code TEXT}；</li>
 *   <li>无原生 {@code BOOLEAN} 关键字（使用 {@code TINYINT} 替代）；</li>
 *   <li>默认禁止 INSERT 显式指定自增列，需会话级参数临时放开；</li>
 *   <li>不支持生成列（{@code GENERATED ALWAYS AS} 直接报错）。</li>
 * </ul>
 */
final class GBase8aDialect extends MySqlFamilyDialect {

    @Override
    protected int decimalPrecisionCap() { return 38; }

    /** GBase 8a 无原生 BOOLEAN，使用 TINYINT 替代。 */
    @Override
    protected DatabaseTypeMapping booleanMapping() { return DatabaseTypeMapping.supported("TINYINT"); }

    /** GBase 8a 不支持 LONGTEXT，超过 VARCHAR 范围统一使用 TEXT。 */
    @Override
    protected String stringDdlType(FieldSchema field) {
        Integer length = field.getLength();
        return length != null && length <= 255 ? "VARCHAR(" + length + ")" : "TEXT";
    }

    /**
     * GBase 8a 默认禁止在 INSERT 中显式指定自增列的值
     * （报 auto-increment column should not be specified in insert），
     * 需要通过会话级参数 _gbase_auto_increment_allow_insert 临时放开，
     * 作用等价于 SQL Server 的 SET IDENTITY_INSERT。
     * 迁移场景必须保留源表主键值，因此写入前后成对开启/关闭。
     */
    @Override
    public String identityInsertOnSql(String table) { return "SET _gbase_auto_increment_allow_insert = 1"; }

    @Override
    public String identityInsertOffSql(String table) { return "SET _gbase_auto_increment_allow_insert = 0"; }

    /**
     * GBase 8a 不支持生成列（GENERATED ALWAYS AS 语法直接报错），
     * 迁移含生成列的源表时必须显式失败，避免产出无法执行的 DDL。
     */
    @Override
    protected String generatedColumn(ColumnInfo column) {
        throw new IllegalArgumentException("GBase 8a 不支持生成列，无法迁移字段 '" + column.getName() + "'");
    }
}
