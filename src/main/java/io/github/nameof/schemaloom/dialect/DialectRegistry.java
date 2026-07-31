package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.driver.DatabaseType;

import java.util.*;

public final class DialectRegistry {
    private final EnumMap<DatabaseType, DatabaseDialect> map = new EnumMap<DatabaseType, DatabaseDialect>(DatabaseType.class);

    public DialectRegistry() {
        map.put(DatabaseType.MYSQL, new MySqlDialect());
        map.put(DatabaseType.ORACLE, new OracleDialect());
        map.put(DatabaseType.SQL_SERVER, new SqlServerDialect());
    }

    public DatabaseDialect get(DatabaseType t) {
        DatabaseDialect d = map.get(t);
        if (d == null) throw new IllegalArgumentException("unsupported database: " + t);
        return d;
    }
}
