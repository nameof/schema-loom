package io.github.nameof.schemaloom.driver;

import io.github.nameof.schemaloom.api.SchemaLoomException;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
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
        this.schema = normalize(schema);
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
    public QualifiedTableName table(String table) { return new QualifiedTableName(catalog, schema, table); }

    @Override public String toString() {
        return "DatabaseConnectionInfo{" + databaseType + " " + host + ":" + port + "/" + database + "}";
    }
}
