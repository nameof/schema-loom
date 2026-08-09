package io.github.nameof.schemaloom.driver;

/** Centralizes creation of independent providers for high-level JDBC components. */
public final class JdbcConnectionFactory {
    private JdbcConnectionFactory() { }
    public static ConnectionProvider open(DatabaseConnectionInfo info) {
        JdbcDriverLoader loader = new JdbcDriverLoader();
        try {
            return open(info, loader);
        } catch (RuntimeException e) {
            loader.close();
            throw e;
        }
    }
    public static ConnectionProvider open(DatabaseConnectionInfo info, JdbcDriverLoader loader) {
        if (info == null) throw new IllegalArgumentException("database connection info is required");
        if (loader == null) throw new IllegalArgumentException("jdbc driver loader is required");
        return loader.connect(info);
    }
}
