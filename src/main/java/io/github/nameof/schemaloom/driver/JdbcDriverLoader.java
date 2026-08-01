package io.github.nameof.schemaloom.driver;

import io.github.nameof.schemaloom.api.SchemaLoomException;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;

import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public final class JdbcDriverLoader implements AutoCloseable {
    private final List<DriverDescriptor> descriptors;
    private final Map<String, Entry> cache = new HashMap<String, Entry>();

    public JdbcDriverLoader() {
        this.descriptors = new DriverDescriptorLoader().load();
    }

    public JdbcDriverLoader(Path root) {
        descriptors = new DriverDescriptorLoader().load(root);
    }

    public List<DriverDescriptor> getDescriptors() {
        return Collections.unmodifiableList(descriptors);
    }

    public synchronized ConnectionProvider connect(String url, String driverId, Properties properties) {
        return connect(null, url, driverId, properties);
    }

    public synchronized ConnectionProvider connect(DatabaseType type, String url, String driverId, Properties properties) {
        List<DriverDescriptor> candidates = new ArrayList<DriverDescriptor>();
        for (DriverDescriptor d : descriptors)
            if ((driverId == null || driverId.equals(d.getId())) && (type == null || type == d.getDatabaseType())
                    && (driverId != null || d.getUrlPrefixes().isEmpty() || matches(d, url)))
                candidates.add(d);
        Collections.sort(candidates, new Comparator<DriverDescriptor>() {
            public int compare(DriverDescriptor a, DriverDescriptor b) {
                int x = Integer.compare(b.getPriority(), a.getPriority());
                return x != 0 ? x : a.getId().compareTo(b.getId());
            }
        });
        if (driverId != null && candidates.isEmpty()) throw new SchemaLoomException("driver not found: " + driverId);
        if (candidates.isEmpty()) throw new SchemaLoomException("no JDBC driver matches URL: " + url);
        Throwable last = null;
        for (DriverDescriptor d : candidates)
            try {
                return open(d, url, properties);
            } catch (Throwable e) {
                last = e;
                if (driverId != null) break;
            }
        throw new SchemaLoomException("no driver could connect to " + url, last);
    }

    private boolean matches(DriverDescriptor d, String url) {
        for (String p : d.getUrlPrefixes()) if (StrUtil.startWith(url, p)) return true;
        return false;
    }

    private synchronized ConnectionProvider open(DriverDescriptor d, String url, Properties supplied) throws Exception {
        Entry e = cache.get(d.getId());
        if (e == null) {
            URL[] us = new URL[d.getClasspath().size()];
            for (int i = 0; i < us.length; i++) us[i] = d.getClasspath().get(i).toUri().toURL();
            DriverClassLoader cl = new DriverClassLoader(us, getClass().getClassLoader(), d.getDriverPackages());
            Driver driver = (Driver) Class.forName(d.getDriverClass(), true, cl).newInstance();
            e = new Entry(cl, driver);
            cache.put(d.getId(), e);
        }
        Properties p = d.getDefaultProperties();
        if (supplied != null) p.putAll(supplied);
        Connection c = e.driver.connect(url, p);
        if (c == null) throw new SQLException("driver rejected URL");
        if (!VersionRange.accept(d.getVersionRange(), c.getMetaData().getDatabaseProductVersion())) {
            try {
                c.close();
            } catch (SQLException ignored) {
            }
            if (e.refs == 0) {
                cache.remove(d.getId());
                try { e.loader.close(); } catch (Exception ignored) { }
            }
            throw new SQLException("server version is outside driver range");
        }
        e.refs++;
        return new Provider(c, e, this);
    }

    public synchronized void release(Entry e) {
        if (--e.refs == 0) try {
            e.loader.close();
            cache.values().remove(e);
        } catch (Exception ex) {
            throw new SchemaLoomException("cannot close driver loader", ex);
        }
    }

    public synchronized void close() {
        for (Entry e : cache.values())
            try {
                e.loader.close();
            } catch (Exception ignored) {
            }
        cache.clear();
    }

    private static final class Entry {
        final DriverClassLoader loader;
        final Driver driver;
        int refs;

        Entry(DriverClassLoader l, Driver d) {
            loader = l;
            driver = d;
        }
    }

    static final class VersionRange {
        static boolean accept(String range, String version) {
            if (range == null || range.trim().isEmpty()) return true;
            String x = range.trim();
            if (x.length() < 2 || (!x.startsWith("[") && !x.startsWith("(")) || (!x.endsWith("]") && !x.endsWith(")")))
                throw new SchemaLoomException("invalid serverVersionRange: " + range);
            String[] p = x.substring(1, x.length() - 1).split(",", -1);
            if (p.length != 2) throw new SchemaLoomException("invalid serverVersionRange: " + range);
            int v = cmp(version, p[0].trim());
            int hi = cmp(version, p[1].trim());
            return (p[0].trim().isEmpty() || (x.charAt(0) == '[' ? v >= 0 : v > 0)) && (p[1].trim().isEmpty() || (x.charAt(x.length() - 1) == ']' ? hi <= 0 : hi < 0));
        }

        private static int cmp(String a, String b) {
            if (b.isEmpty()) return 0;
            String numeric = ReUtil.getGroup0("\\d+(?:\\.\\d+)*", StrUtil.emptyIfNull(a));
            if (StrUtil.isBlank(numeric)) return -1;
            String[] x = numeric.split("\\.");
            String[] y = b.split("\\.");
            for (int i = 0; i < Math.max(x.length, y.length); i++) {
                int m = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
                int n = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
                if (m != n) return m < n ? -1 : 1;
            }
            return 0;
        }
    }

    private static final class Provider implements ConnectionProvider {
        private Connection c;
        private Entry e;
        private JdbcDriverLoader owner;

        Provider(Connection c, Entry e, JdbcDriverLoader o) {
            this.c = c;
            this.e = e;
            owner = o;
        }

        public Connection getConnection() {
            if (c == null) throw new SchemaLoomException("connection is closed");
            return c;
        }

        public void close() {
            if (c != null) try {
                c.close();
            } catch (Exception ignored) {
            } finally {
                c = null;
                owner.release(e);
            }
        }
    }
}
