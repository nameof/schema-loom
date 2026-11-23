package io.github.nameof.schemaloom.dialect;

/**
 * MariaDB 方言：DECIMAL 精度上限、注释转义、视图查询等与 MySQL 完全一致，
 * 因此直接继承 {@link MySqlFamilyDialect} 的家族默认实现，不做覆盖。
 */
final class MariaDbDialect extends MySqlFamilyDialect {
}
