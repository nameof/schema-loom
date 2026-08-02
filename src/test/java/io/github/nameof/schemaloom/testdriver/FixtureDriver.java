package io.github.nameof.schemaloom.testdriver;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/** Minimal JDBC driver copied into two temporary JARs for class-loader tests. */
public final class FixtureDriver implements Driver {
    public Connection connect(String url, Properties info) throws SQLException {
        if (url == null || !url.startsWith("jdbc:fixture:")) return null;
        final String version = version();
        InvocationHandler metadata = new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("getDatabaseProductVersion".equals(name)) return version;
                if ("getDatabaseProductName".equals(name)) return "FixtureDB";
                if ("getURL".equals(name)) return "jdbc:fixture:test";
                return defaultValue(method.getReturnType());
            }
        };
        final DatabaseMetaData dbmd = (DatabaseMetaData) Proxy.newProxyInstance(
                FixtureDriver.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, metadata);
        InvocationHandler connection = new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("getMetaData".equals(method.getName())) return dbmd;
                if ("isClosed".equals(method.getName())) return false;
                return defaultValue(method.getReturnType());
            }
        };
        return (Connection) Proxy.newProxyInstance(FixtureDriver.class.getClassLoader(),
                new Class<?>[]{Connection.class}, connection);
    }

    private String version() throws SQLException {
        InputStream in = FixtureDriver.class.getResourceAsStream("/driver-version.txt");
        if (in == null) return "8.0.0";
        try {
            byte[] bytes = new byte[32];
            int n = in.read(bytes);
            return new String(bytes, 0, n, "UTF-8").trim();
        } catch (IOException e) {
            throw new SQLException("cannot read fixture driver version", e);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    public boolean acceptsURL(String url) { return url != null && url.startsWith("jdbc:fixture:"); }
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    public int getMajorVersion() { return 1; }
    public int getMinorVersion() { return 0; }
    public boolean jdbcCompliant() { return false; }
    public Logger getParentLogger() { return Logger.getGlobal(); }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
