package io.github.nameof.schemaloom.driver;

import io.github.nameof.schemaloom.api.SchemaLoomException;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;

import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public final class JdbcDriverLoader implements AutoCloseable {
    // 此缓存只保存驱动实例和类加载器，不保存 JDBC Connection。
    private final List<DriverDescriptor> descriptors;
    private final Map<String, Entry> cache = new HashMap<String, Entry>();

    /** 使用线程上下文 classloader 发现默认 drivers 目录。 */
    public JdbcDriverLoader() {
        this.descriptors = new DriverDescriptorLoader().load();
    }

    /** 使用指定目录中的驱动描述，便于隔离测试或外部配置。 */
    public JdbcDriverLoader(Path root) {
        descriptors = new DriverDescriptorLoader().load(root);
    }

    public List<DriverDescriptor> getDescriptors() {
        return Collections.unmodifiableList(descriptors);
    }

    public synchronized ConnectionProvider connect(String url, String driverId, Properties properties) {
        // 每次调用都会创建新的 provider，因而创建新的 JDBC Connection。
        return connect(null, url, driverId, properties);
    }

    /** 通过完整连接配置按数据库类型、优先级和服务端版本自动选择驱动。 */
    public synchronized ConnectionProvider connect(DatabaseConnectionInfo config) {
        if (config == null) {
            throw new SchemaLoomException("connection config is required");
        }
        String driverId = config.getDriverId();
        List<DriverDescriptor> candidates = new ArrayList<>();
        for (DriverDescriptor d : descriptors) {
            if ((driverId == null || driverId.equals(d.getId())) && config.getDatabaseType() == d.getDatabaseType())
                candidates.add(d);
        }
        sort(candidates);
        if (driverId != null && candidates.isEmpty()) {
            throw new SchemaLoomException("driver not found: " + driverId);
        }
        if (candidates.isEmpty()) {
            throw new SchemaLoomException("no JDBC driver matches database type: " + config.getDatabaseType());
        }
        Throwable last = null;
        for (DriverDescriptor d : candidates) {
            try {
                String url = JdbcUrlBuilder.build(config.getDatabaseType(), d.getUrlTemplate(), config.getHost(), config.getPort(), config.getDatabase());
                // 自动选择时，模板生成的 URL 仍需符合描述文件声明的前缀。
                if (driverId == null && !d.getUrlPrefixes().isEmpty() && !matches(d, url)) continue;
                Properties p = config.getProperties();
                if (config.getUsername() != null) p.setProperty("user", config.getUsername());
                if (config.getPassword() != null) p.setProperty("password", config.getPassword());
                return open(d, url, p);
            } catch (Throwable e) {
                last = e;
                // 未指定驱动时允许降级尝试下一个候选；指定驱动则保留原始失败原因。
                if (driverId != null) break;
            }
        }
        throw new SchemaLoomException("no driver could connect to database: " + config.getDatabaseType(), last);
    }

    /** 按 URL 选择驱动；显式 driverId 会跳过前缀筛选并只尝试该驱动。 */
    public synchronized ConnectionProvider connect(DatabaseType type, String url, String driverId, Properties properties) {
        List<DriverDescriptor> candidates = new ArrayList<DriverDescriptor>();
        for (DriverDescriptor d : descriptors)
            if ((driverId == null || driverId.equals(d.getId())) && (type == null || type == d.getDatabaseType())
                    && (driverId != null || d.getUrlPrefixes().isEmpty() || matches(d, url)))
                candidates.add(d);
        sort(candidates);
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

    /** 优先级越高越先尝试；同优先级按 id 排序，保证选择结果稳定。 */
    private static void sort(List<DriverDescriptor> candidates) {
        Collections.sort(candidates, new Comparator<DriverDescriptor>() {
            public int compare(DriverDescriptor a, DriverDescriptor b) {
                int x = Integer.compare(b.getPriority(), a.getPriority());
                return x != 0 ? x : a.getId().compareTo(b.getId());
            }
        });
    }

    /** 判断 URL 是否以描述文件声明的任一前缀开头。 */
    private boolean matches(DriverDescriptor d, String url) {
        for (String p : d.getUrlPrefixes()) if (StrUtil.startWith(url, p)) return true;
        return false;
    }

    /** 创建或复用隔离的驱动实例，并为每个连接记录一次引用。 */
    /**
     * 复用驱动实例和类加载器，但每次调用都会创建新的 JDBC Connection 和 provider；
     * 返回的 provider 负责持有并关闭这个连接。
     */
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
        // 从描述文件默认值复制，再覆盖调用方参数，避免污染不可变的描述对象。
        Properties p = d.getDefaultProperties();
        if (supplied != null) p.putAll(supplied);
        // Driver.connect 创建新的数据库会话，不会复用缓存中的 Connection。
        Connection c = e.driver.connect(url, p);
        if (c == null) {
            throw new SQLException("driver rejected URL");
        }
        if (!VersionRange.accept(d.getVersionRange(), c.getMetaData().getDatabaseProductVersion())) {
            // 版本不匹配的连接不能交给调用方；若没有其他引用，同时释放驱动类加载器。
            try {
                c.close();
            } catch (SQLException ignored) {
            }
            if (e.refs == 0) {
                cache.remove(d.getId());
                try {
                    e.loader.close();
                } catch (Exception ignored) {
                }
            }
            throw new SQLException("server version is outside driver range");
        }
        e.refs++;
        return new Provider(c, e, this);
    }

    /** 关闭一个连接后减少引用；最后一个连接释放时卸载对应驱动类加载器。 */
    public synchronized void release(Entry e) {
        if (--e.refs == 0) try {
            e.loader.close();
            cache.values().remove(e);
        } catch (Exception ex) {
            throw new SchemaLoomException("cannot close driver loader", ex);
        }
    }

    /** 关闭所有已缓存驱动，供 loader 本身释放资源。 */
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
        /** 判断版本是否落在形如 [5.7,8.0) 的闭开区间内。 */
        static boolean accept(String range, String version) {
            if (range == null || range.trim().isEmpty()) return true;
            String x = range.trim();
            if (x.length() < 2 || (!x.startsWith("[") && !x.startsWith("(")) || (!x.endsWith("]") && !x.endsWith(")")))
                throw new SchemaLoomException("invalid serverVersionRange: " + range);
            String[] p = x.substring(1, x.length() - 1).split(",", -1);
            if (p.length != 2) throw new SchemaLoomException("invalid serverVersionRange: " + range);
            // 分别与下界和上界比较；空边界表示无穷边界，括号决定是否包含端点。
            int v = cmp(version, p[0].trim());
            int hi = cmp(version, p[1].trim());
            return (p[0].trim().isEmpty() || (x.charAt(0) == '[' ? v >= 0 : v > 0)) && (p[1].trim().isEmpty() || (x.charAt(x.length() - 1) == ']' ? hi <= 0 : hi < 0));
        }

        /** 提取版本中的数字段逐段比较，避免将后缀文本误当作版本数值。 */
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
            // 多次调用有意返回同一个数据库会话，以保持事务状态和流式 ResultSet 的归属一致。
            if (c == null) throw new SchemaLoomException("connection is closed");
            return c;
        }

        /** 连接关闭必须幂等，同时归还 loader 中的驱动引用。 */
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
