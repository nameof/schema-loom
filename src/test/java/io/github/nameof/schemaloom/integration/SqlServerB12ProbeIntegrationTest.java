package io.github.nameof.schemaloom.integration;

import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.driver.DatabaseType;
import io.github.nameof.schemaloom.driver.JdbcDriverLoader;
import io.github.nameof.schemaloom.metadata.DatabaseMetadataService;
import io.github.nameof.schemaloom.metadata.MetadataQuery;
import io.github.nameof.schemaloom.metadata.TableInfo;
import org.junit.Assume;
import org.junit.Test;
import schemacrawler.schema.Catalog;
import schemacrawler.schema.Schema;
import schemacrawler.schema.Table;
import schemacrawler.schemacrawler.SchemaCrawlerOptionsBuilder;
import schemacrawler.tools.utility.SchemaCrawlerUtility;
import us.fatehi.utility.datasource.DatabaseConnectionSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * B12 复现探针：SQL Server 会话连在 master 时，SchemaCrawler 枚举到的表不是当前库的表。
 * <p>
 * 连接信息从环境变量读取（{@code SCHEMALOOM_IT_SQLSERVER_*}），读不到即跳过。
 * 预期 ground truth：master 库有 12 张 sl_* 测试表，user_info 在 sl_source / sl_target 库。
 */
public class SqlServerB12ProbeIntegrationTest {

    private static final DatabaseMetadataService SERVICE = new DatabaseMetadataService();

    /** B12 主探针：连 master，对比驱动原始元数据 / SchemaCrawler 枚举 / 项目 API 三路结果。 */
    @Test
    public void probesB12WhenConnectedToMaster() throws Exception {
        DatabaseConnectionInfo config = sqlServer(null);
        Assume.assumeNotNull("缺少 SQL Server 连接环境变量，跳过", config);

        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            System.out.println("===== [B12] 当前连接与 ground truth =====");
            // provider.getConnection() 是共享单连接，不能在测试里直接 close，统一由 provider.close() 关闭
            Connection connection = provider.getConnection();
            try (PreparedStatement ps = connection.prepareStatement("SELECT DB_NAME()")) {
                try (ResultSet rs = ps.executeQuery()) { rs.next(); System.out.println("  DB_NAME()=" + rs.getString(1)); }
            }
            rawJdbcTables(provider, "master");
            rawJdbcTables(provider, "sl_source");

            System.out.println("===== [B12] SchemaCrawler 无过滤枚举（连 master） =====");
            Catalog catalog = SchemaCrawlerUtility.getCatalog(connectionSource(provider),
                    SchemaCrawlerOptionsBuilder.newSchemaCrawlerOptions());
            System.out.println("  SchemaCrawler 表总数=" + catalog.getTables().size());
            for (Table t : catalog.getTables())
                System.out.println("  [SC] " + t.getSchema().getCatalogName() + "." + t.getSchema().getName() + "." + t.getName());

            System.out.println("===== [B12] 项目 API listTables 各模式命中数 =====");
            projectListTables(provider, new MetadataQuery(null, null, "%"));
            projectListTables(provider, new MetadataQuery(null, null, "sl%"));
            projectListTables(provider, new MetadataQuery(null, null, "sl_null_src"));
            projectListTables(provider, new MetadataQuery("master", null, "%"));
            projectListTables(provider, new MetadataQuery("sl_source", null, "%"));
            projectListTables(provider, new MetadataQuery(null, "dbo", "%"));
        }
    }

    /** 修法预演：SC_WITHOUT_DATABASE_PLUGIN=sqlserver 旁路插件后，枚举是否跟随连接库（master 应可见）。 */
    @Test
    public void probesB12GenericJdbcModeFromMaster() throws Exception {
        DatabaseConnectionInfo config = sqlServer(null);
        Assume.assumeNotNull("缺少 SQL Server 连接环境变量，跳过", config);

        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            System.out.println("===== [B12-旁路] SC_WITHOUT_DATABASE_PLUGIN=sqlserver，连 master =====");
            String previous = System.getProperty("SC_WITHOUT_DATABASE_PLUGIN");
            System.setProperty("SC_WITHOUT_DATABASE_PLUGIN", "sqlserver");
            try {
                Catalog catalog = SchemaCrawlerUtility.getCatalog(connectionSource(provider),
                        SchemaCrawlerOptionsBuilder.newSchemaCrawlerOptions());
                System.out.println("  SchemaCrawler 表总数=" + catalog.getTables().size());
                for (Table t : catalog.getTables())
                    System.out.println("  [SC-BYPASS] " + t.getSchema().getCatalogName() + "." + t.getSchema().getName() + "." + t.getName());
            } finally {
                if (previous == null) System.clearProperty("SC_WITHOUT_DATABASE_PLUGIN");
                else System.setProperty("SC_WITHOUT_DATABASE_PLUGIN", previous);
            }
        }
    }

    /** 对照组：连 sl_source 时 SchemaCrawler 枚举是否跟随连接库。 */
    @Test
    public void probesB12WhenConnectedToSlSource() throws Exception {
        DatabaseConnectionInfo config = sqlServer("sl_source");
        Assume.assumeNotNull("缺少 SQL Server 连接环境变量，跳过", config);

        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            System.out.println("===== [B12-对照] 连 sl_source =====");
            Connection connection = provider.getConnection();
            try (PreparedStatement ps = connection.prepareStatement("SELECT DB_NAME()")) {
                try (ResultSet rs = ps.executeQuery()) { rs.next(); System.out.println("  DB_NAME()=" + rs.getString(1)); }
            }
            Catalog catalog = SchemaCrawlerUtility.getCatalog(connectionSource(provider),
                    SchemaCrawlerOptionsBuilder.newSchemaCrawlerOptions());
            System.out.println("  SchemaCrawler 表总数=" + catalog.getTables().size());
            for (Table t : catalog.getTables())
                System.out.println("  [SC] " + t.getSchema().getCatalogName() + "." + t.getSchema().getName() + "." + t.getName());
        }
    }

    /** 驱动原始 DatabaseMetaData.getTables 按显式 catalog 盘点。 */
    private void rawJdbcTables(ConnectionProvider provider, String db) throws Exception {
        DatabaseMetaData meta = provider.getConnection().getMetaData();
        int count = 0;
        try (ResultSet rs = meta.getTables(db, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                count++;
                System.out.println("  [RAW/" + db + "] " + rs.getString("TABLE_CAT") + "." + rs.getString("TABLE_SCHEM") + "." + rs.getString("TABLE_NAME"));
            }
        }
        System.out.println("  [RAW/" + db + "] 共 " + count + " 张");
    }

    private void projectListTables(ConnectionProvider provider, MetadataQuery query) {
        try {
            List<TableInfo> tables = SERVICE.listTables(provider, query);
            System.out.println("  [API] catalog=" + query.getCatalog() + " schema=" + query.getSchema()
                    + " pattern=" + query.getTablePattern() + " → " + tables.size() + " 张");
            for (TableInfo t : tables)
                System.out.println("  [API]   " + t.getName());
        } catch (Exception e) {
            System.out.println("  [API] catalog=" + query.getCatalog() + " schema=" + query.getSchema()
                    + " pattern=" + query.getTablePattern() + " → 异常: " + e.getMessage());
        }
    }

    /** SQL Server 连接配置，database 可覆盖（对照组用），缺少用户/口令时返回 null。 */
    private static DatabaseConnectionInfo sqlServer(String databaseOverride) {
        String user = System.getenv("SCHEMALOOM_IT_SQLSERVER_USER");
        String password = System.getenv("SCHEMALOOM_IT_SQLSERVER_PASSWORD");
        if (user == null || password == null) return null;
        return new DatabaseConnectionInfo(DatabaseType.SQL_SERVER,
                env("SCHEMALOOM_IT_SQLSERVER_HOST", "localhost"),
                Integer.parseInt(env("SCHEMALOOM_IT_SQLSERVER_PORT", "11433")),
                databaseOverride != null ? databaseOverride : env("SCHEMALOOM_IT_SQLSERVER_DATABASE", "master"),
                user, password,
                env("SCHEMALOOM_IT_SQLSERVER_DRIVER_ID", "sqlserver2022"), null);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** 让 SchemaCrawler 关闭连接时不影响我们自己持有的连接。 */
    private static DatabaseConnectionSource connectionSource(final ConnectionProvider provider) {
        return new DatabaseConnectionSource() {
            public Connection get() { return proxy(provider.getConnection()); }
            public boolean releaseConnection(Connection connection) { return false; }
            public void setFirstConnectionInitializer(java.util.function.Consumer<Connection> initializer) { initializer.accept(get()); }
            public void close() { }
        };
    }

    private static Connection proxy(final Connection target) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                SqlServerB12ProbeIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new java.lang.reflect.InvocationHandler() {
                    public Object invoke(Object p, java.lang.reflect.Method method, Object[] args) throws Throwable {
                        if ("close".equals(method.getName())) return null;
                        try {
                            return method.invoke(target, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
    }
}
