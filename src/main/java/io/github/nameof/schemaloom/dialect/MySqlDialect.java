package io.github.nameof.schemaloom.dialect;

/**
 * MySQL 方言：与 {@link MySqlFamilyDialect} 的家族默认行为完全一致，无需覆盖。
 *
 * <p>刻意保留为独立具体类而不是直接注册基类，是因为 {@code DialectRegistry} 按
 * {@code DatabaseType} 一对一映射，三者必须可区分。差异点若将来出现，加在此处即可。</p>
 */
final class MySqlDialect extends MySqlFamilyDialect {
}
