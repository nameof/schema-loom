package io.github.nameof.schemaloom.driver;

import io.github.nameof.schemaloom.api.SchemaLoomException;

import java.util.Properties;

public final class JdbcConnectionConfig {
    private final DatabaseType databaseType;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String driverId;
    private final Properties properties;

    public JdbcConnectionConfig(DatabaseType databaseType, String host, int port, String database,
                                String username, String password) {
        this(databaseType, host, port, database, username, password, null, null);
    }

    public JdbcConnectionConfig(DatabaseType databaseType, String host, int port, String database,
                                String username, String password, String driverId, Properties properties) {
        if (databaseType == null || host == null || host.trim().isEmpty() || database == null || database.trim().isEmpty())
            throw new SchemaLoomException("databaseType, host and database are required");
        if (port < 0 || port > 65535) throw new SchemaLoomException("invalid database port: " + port);
        this.databaseType = databaseType;
        this.host = host.trim();
        this.port = port == 0 ? JdbcUrlBuilder.defaultPort(databaseType) : port;
        this.database = database.trim();
        this.username = username;
        this.password = password;
        this.driverId = driverId == null || driverId.trim().isEmpty() ? null : driverId.trim();
        this.properties = new Properties();
        if (properties != null) this.properties.putAll(properties);
    }

    public DatabaseType getDatabaseType() { return databaseType; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getDatabase() { return database; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getDriverId() { return driverId; }
    public Properties getProperties() { Properties p = new Properties(); p.putAll(properties); return p; }
}
