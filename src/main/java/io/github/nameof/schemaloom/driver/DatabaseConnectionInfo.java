package io.github.nameof.schemaloom.driver;

import io.github.nameof.schemaloom.api.SchemaLoomException;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import java.util.Locale;
import java.util.Properties;

/** Immutable connection and default database namespace information. */
public class DatabaseConnectionInfo {
    private final DatabaseType databaseType;
    private final String host;
    private final int port;
    private final String database, catalog, schema, username, password, driverId;
    private final Properties properties;

    public DatabaseConnectionInfo(DatabaseType type, String host, int port, String database, String user, String password) {
        this(type, host, port, database, null, null, user, password, null, null);
    }

    public DatabaseConnectionInfo(DatabaseType type, String host, int port, String database,
                                  String user, String password, String driverId, Properties properties) {
        this(type, host, port, database, null, null, user, password, driverId, properties);
    }

    public DatabaseConnectionInfo(DatabaseType type, String host, int port, String database,
                                  String catalog, String schema, String user, String password,
                                  String driverId, Properties properties) {
        if (type == null || host == null || host.trim().isEmpty() || database == null || database.trim().isEmpty())
            throw new SchemaLoomException("databaseType, host and database are required");
        if (port < 0 || port > 65535) throw new SchemaLoomException("invalid database port: " + port);
        this.databaseType = type;
        this.host = host.trim();
        this.port = port == 0 ? JdbcUrlBuilder.defaultPort(type) : port;
        this.database = database.trim();
        this.catalog = normalize(catalog);
        String configuredSchema = normalize(schema);
        this.schema = (type == DatabaseType.POSTGRESQL || type == DatabaseType.KINGBASE) && configuredSchema == null ? "public" : configuredSchema;
        this.username = user;
        this.password = password;
        this.driverId = normalize(driverId);
        this.properties = new Properties();
        if (properties != null) this.properties.putAll(properties);
    }

    private static String normalize(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    public DatabaseType getDatabaseType() { return databaseType; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getDatabase() { return database; }
    public String getCatalog() { return catalog; }
    public String getSchema() { return schema; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getDriverId() { return driverId; }
    public Properties getProperties() { Properties p = new Properties(); p.putAll(properties); return p; }
    /**
     * 构造目标表限定名。DB2 / 达梦 / Oracle 未加引号的标识符在此规范化为大写，保证表名不带引号也能被查到。
     * 使用者若需要精确命中库里的大小写敏感对象，用双引号包裹传入（{@code "\"mytable\""}）即可跳过规范化。
     */
    public QualifiedTableName table(String table) {
        return new QualifiedTableName(catalog, schema, normalizeIdentifier(table));
    }

    private String normalizeIdentifier(String table) {
        if (table == null) return null;
        String trimmed = table.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            String unquoted = trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
            if (!unquoted.trim().isEmpty()) return unquoted;
        }
        return uppercasesIdentifiers() ? trimmed.toUpperCase(Locale.ENGLISH) : trimmed;
    }

    /** DB2 / 达梦 / Oracle 的未加引号标识符按大写存储。 */
    private boolean uppercasesIdentifiers() {
        return databaseType == DatabaseType.DB2 || databaseType == DatabaseType.DM || databaseType == DatabaseType.ORACLE;
    }

    @Override public String toString() {
        return "DatabaseConnectionInfo{" + databaseType + " " + host + ":" + port + "/" + database + "}";
    }
}
