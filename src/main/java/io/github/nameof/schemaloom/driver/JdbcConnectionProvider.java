package io.github.nameof.schemaloom.driver;

import io.github.nameof.schemaloom.api.SchemaLoomException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * A small explicit-URL provider, useful for integrations and tests.
 */
public final class JdbcConnectionProvider implements ConnectionProvider {
    private final String url;
    private final Properties properties;
    private Connection connection;

    public JdbcConnectionProvider(String url, String user, String password) {
        if (url == null || url.trim().isEmpty()) throw new IllegalArgumentException("url is blank");
        this.url = url;
        this.properties = new Properties();
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
    }

    /**
     * 为当前 provider 延迟创建且只创建一个连接。synchronized 只保护连接创建过程；
     * 返回的 JDBC Connection 仍是有状态对象，不能被多个 ETL 任务并发共享。
     */
    public synchronized Connection getConnection() {
        if (connection == null) try {
            connection = DriverManager.getConnection(url, properties);
        } catch (SQLException e) {
            throw new SchemaLoomException("cannot open JDBC connection", e);
        }
        return connection;
    }

    /** 关闭 provider 时，同时关闭它所持有的唯一连接。 */
    public synchronized void close() {
        if (connection != null) try {
            connection.close();
        } catch (SQLException ignored) {
        } finally {
            connection = null;
        }
    }
}
