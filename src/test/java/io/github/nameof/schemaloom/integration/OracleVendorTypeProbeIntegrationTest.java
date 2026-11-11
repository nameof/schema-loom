package io.github.nameof.schemaloom.integration;

import io.github.nameof.schemaloom.api.EtlError;
import io.github.nameof.schemaloom.api.EtlResult;
import io.github.nameof.schemaloom.api.MetadataErrorPolicy;
import io.github.nameof.schemaloom.api.TargetMode;
import io.github.nameof.schemaloom.dialect.DatabaseDialect;
import io.github.nameof.schemaloom.dialect.DatabaseTypeMapping;
import io.github.nameof.schemaloom.dialect.DialectRegistry;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.driver.DatabaseType;
import io.github.nameof.schemaloom.driver.JdbcDriverLoader;
import io.github.nameof.schemaloom.engine.EtlTask;
import io.github.nameof.schemaloom.metadata.ColumnInfo;
import io.github.nameof.schemaloom.metadata.DatabaseMetadataService;
import io.github.nameof.schemaloom.metadata.MetadataQuery;
import io.github.nameof.schemaloom.metadata.TableInfo;
import io.github.nameof.schemaloom.source.JdbcTableSource;
import io.github.nameof.schemaloom.target.JdbcTableTarget;
import org.junit.Assume;
import org.junit.Test;
import schemacrawler.schema.Catalog;
import schemacrawler.schema.Table;
import schemacrawler.schemacrawler.SchemaCrawlerOptionsBuilder;
import schemacrawler.tools.utility.SchemaCrawlerUtility;
import us.fatehi.utility.datasource.DatabaseConnectionSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Oracle 厂商类型码（C7）与表名模式（B11）的实机回归探针。
 * <p>
 * 连接信息从环境变量读取（{@code SCHEMALOOM_IT_*}），读不到即跳过，
 * 避免把容器口令写进源码。探针依赖 Oracle 中的 {@code SL_SOURCE.SL_FLOAT_PROBE}：
 * <pre>
 * CREATE TABLE sl_float_probe (id NUMBER(10) PRIMARY KEY, bf BINARY_FLOAT, bd BINARY_DOUBLE,
 *                              fl FLOAT, rl REAL);
 * INSERT INTO sl_float_probe VALUES (1, 1.23456789012345, 1.23456789012345, 1.23456789012345, 1.23456789012345);
 * </pre>
 */
public class OracleVendorTypeProbeIntegrationTest {

    private static final String PROBE_TABLE = "SL_FLOAT_PROBE";
    private static final String PROBE_SCHEMA = "SL_SOURCE";

    /** C7：驱动原始码 vs SchemaCrawler 归一化值，说明为什么按类型名识别。 */
    @Test
    public void probesRawAndSchemaCrawlerCodes() throws Exception {
        DatabaseConnectionInfo config = oracle();
        Assume.assumeNotNull("缺少 Oracle 连接环境变量，跳过", config);

        System.out.println("===== C7 原始 JDBC 元数据（getColumns） =====");
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            DatabaseMetaData meta = provider.getConnection().getMetaData();
            try (ResultSet rs = meta.getColumns(null, PROBE_SCHEMA, PROBE_TABLE, null)) {
                while (rs.next())
                    System.out.println("  [RAW] " + rs.getString("COLUMN_NAME")
                            + " dataType=" + rs.getInt("DATA_TYPE")
                            + " typeName=" + rs.getString("TYPE_NAME")
                            + " columnSize=" + rs.getInt("COLUMN_SIZE")
                            + " decimalDigits=" + (rs.getObject("DECIMAL_DIGITS") == null ? "null" : rs.getInt("DECIMAL_DIGITS")));
            }
        }

        System.out.println("===== C7 SchemaCrawler 归一化值 =====");
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             final ConnectionProvider provider = loader.connect(config)) {
            Catalog catalog = SchemaCrawlerUtility.getCatalog(connectionSource(provider),
                    SchemaCrawlerOptionsBuilder.newSchemaCrawlerOptions());
            for (Table table : catalog.getTables()) {
                if (!table.getName().equalsIgnoreCase(PROBE_TABLE)) continue;
                for (schemacrawler.schema.Column column : table.getColumns())
                    System.out.println("  [SC] " + column.getName()
                            + " typeName=" + column.getColumnDataType().getDatabaseSpecificTypeName()
                            + " javaSqlType=" + column.getColumnDataType().getJavaSqlType()
                            + " vendorType=" + column.getColumnDataType().getJavaSqlType().getVendorTypeNumber()
                            + " size=" + column.getSize()
                            + " decimalDigits=" + column.getDecimalDigits());
            }
        }
    }

    /** 诊断：Oracle 驱动直接读 BINARY_FLOAT / BINARY_DOUBLE 的取值情况。 */
    @Test
    public void probesOracleFloatRead() throws Exception {
        DatabaseConnectionInfo config = oracle();
        Assume.assumeNotNull("缺少 Oracle 连接环境变量，跳过", config);

        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config);
             Connection connection = provider.getConnection();
             java.sql.PreparedStatement ps = connection.prepareStatement("SELECT * FROM " + PROBE_SCHEMA + "." + PROBE_TABLE)) {
            ps.setFetchSize(10);
            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData m = rs.getMetaData();
                for (int i = 1; i <= m.getColumnCount(); i++)
                    System.out.println("  [RSMD] " + m.getColumnLabel(i)
                            + " type=" + m.getColumnType(i)
                            + " typeName=" + m.getColumnTypeName(i)
                            + " precision=" + m.getPrecision(i)
                            + " scale=" + m.getScale(i));
                while (rs.next()) {
                    System.out.println("  [READ] id=" + rs.getObject(1)
                            + " bf=" + rs.getObject(2) + "(" + rs.getObject(2).getClass().getSimpleName() + ")"
                            + " bd=" + rs.getObject(3) + "(" + rs.getObject(3).getClass().getSimpleName() + ")"
                            + " fl=" + rs.getObject(4) + "(" + rs.getObject(4).getClass().getSimpleName() + ")"
                            + " rl=" + rs.getObject(5) + "(" + rs.getObject(5).getClass().getSimpleName() + ")");
                }
            }
        } catch (Throwable e) {
            System.out.println("  [READ] 失败 " + e.getClass().getName() + ": " + e.getMessage());
            for (StackTraceElement frame : e.getStackTrace()) {
                if (!frame.getClassName().startsWith("io.github.nameof.schemaloom")) continue;
                System.out.println("  [READ]   at " + frame);
            }
        }

        System.out.println("===== Schemaloom 读取封装 =====");
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             JdbcTableSource source = new JdbcTableSource(config, PROBE_TABLE, loader, 10)) {
            for (io.github.nameof.schemaloom.api.DataRecord record : source.preview(10))
                System.out.println("  [SRC] " + record);
        } catch (Throwable e) {
            for (Throwable t = e; t != null; t = t.getCause())
                System.out.println("  [SRC] " + t.getClass().getName() + ": " + t.getMessage());
            for (StackTraceElement frame : e.getStackTrace()) {
                if (!frame.getClassName().startsWith("io.github.nameof.schemaloom")) continue;
                System.out.println("  [SRC]   at " + frame);
            }
        }
    }

    /**
     * C9：Oracle {@code DATE} 列在预览时必须能收敛成 {@code LocalDate}。
     * <p>
     * 修复前 {@code SL_SOURCE.USER_INFO.CREATED_AT} 必抛
     * {@code logical type DATE requires LocalDate but received LocalDateTime}：
     * SchemaCrawler 把该列判成 DATE，驱动的 {@code ResultSetMetaData} 却报成 TIMESTAMP。
     */
    @Test
    public void previewsOracleDateColumn() throws Exception {
        DatabaseConnectionInfo config = oracle();
        Assume.assumeNotNull("缺少 Oracle 连接环境变量，跳过", config);

        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             JdbcTableSource source = new JdbcTableSource(config, "USER_INFO", loader, 10)) {
            List<io.github.nameof.schemaloom.api.DataRecord> rows = source.preview(2);
            System.out.println("  [DATE] schema=" + source.schema());
            assertTrue("USER_INFO 预览应至少返回 1 行", !rows.isEmpty());
            List<Object> dates = new java.util.ArrayList<Object>();
            for (io.github.nameof.schemaloom.api.DataRecord row : rows) {
                Object createdAt = row.get("CREATED_AT");
                dates.add(createdAt);
                System.out.println("  [DATE] CREATED_AT=" + createdAt
                        + " (" + (createdAt == null ? "null" : createdAt.getClass().getSimpleName()) + ")");
                assertTrue("CREATED_AT 应为 LocalDate，实际 " + createdAt,
                        createdAt instanceof java.time.LocalDate);
            }
            assertTrue("CREATED_AT 取值应落在探针数据 2026-01-05 / 2026-02-11 内，实际 " + dates,
                    dates.contains(java.time.LocalDate.of(2026, 1, 5)) || dates.contains(java.time.LocalDate.of(2026, 2, 11)));
        }
    }

    /** 回归排查：SQL Server 按表名查询（确认 B11 的改动没有影响 Oracle 之外的库）。 */
    @Test
    public void probesSqlServerTableMatching() throws Exception {
        DatabaseConnectionInfo config = sqlServer();
        Assume.assumeNotNull("缺少 SQL Server 连接环境变量，跳过", config);

        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            DatabaseMetadataService service = new DatabaseMetadataService();
            try (Statement s = provider.getConnection().createStatement();
                 ResultSet rs = s.executeQuery("SELECT DB_NAME(), SUSER_NAME()")) {
                rs.next();
                System.out.println("  [MSSQL] 实际连接库=" + rs.getString(1) + " 登录名=" + rs.getString(2)
                        + " 配置库=" + config.getDatabase());
            }
            for (String pattern : new String[]{"%", "sl%", "sl_null_src", "sl_c6_ss", "sl_nb"})
                System.out.println("  [MSSQL] pattern=" + pattern + " -> "
                        + service.listTables(provider, new MetadataQuery(null, null, pattern)).size());
            for (TableInfo table : service.listTables(provider, new MetadataQuery(null, null, "%")))
                System.out.println("  [MSSQL-NAME] " + table.getName().getSchema() + "." + table.getName().getTable()
                        + " type=" + table.getType());
        }
    }

    /** C7 断言：BINARY_FLOAT → FLOAT32、BINARY_DOUBLE → FLOAT64，且 NUMBER 负标度不再打挂元数据。 */
    @Test
    public void mapsOracleBinaryFloatToLogicalTypes() throws Exception {
        DatabaseConnectionInfo config = oracle();
        Assume.assumeNotNull("缺少 Oracle 连接环境变量，跳过", config);

        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            DatabaseDialect dialect = new DialectRegistry().get(DatabaseType.ORACLE);
            Map<String, ColumnInfo> columns = new LinkedHashMap<String, ColumnInfo>();
            // 不加表名过滤：Oracle 上 includeTables 不再下推，顺带覆盖「USER_INFO.ID 未指定精度」
            // 这条曾经让整个 listTables 抛 scale must be non-negative 的路径
            List<TableInfo> tables = new DatabaseMetadataService().listTables(provider,
                    new MetadataQuery(null, PROBE_SCHEMA, "%"));
            for (TableInfo table : tables)
                if (table.getName().getTable().equalsIgnoreCase(PROBE_TABLE))
                    for (ColumnInfo column : table.getColumns()) columns.put(column.getName(), column);

            assertTrue("探针表未读到：" + PROBE_TABLE, !columns.isEmpty());
            for (ColumnInfo column : columns.values()) {
                DatabaseTypeMapping mapping = dialect.mapping(column.getLogicalType());
                System.out.println("  [SL] " + column.getName()
                        + " logical=" + column.getLogicalType()
                        + " jdbcType=" + column.getJdbcType()
                        + " typeName=" + column.getTypeName()
                        + " 目标 DDL=" + (mapping == null ? "无映射" : mapping.getDdlType(column.getFieldSchema())));
            }
            assertEquals("BINARY_FLOAT 应为 FLOAT32", "FLOAT32", String.valueOf(columns.get("BF").getLogicalType()));
            assertEquals("BINARY_DOUBLE 应为 FLOAT64", "FLOAT64", String.valueOf(columns.get("BD").getLogicalType()));
            assertEquals("Oracle FLOAT 应为 FLOAT64", "FLOAT64", String.valueOf(columns.get("FL").getLogicalType()));
        }
    }

    /** B11 断言：Oracle 上按表名查询必须命中，之前除 % 之外全部落空。 */
    @Test
    public void findsOracleTableByExactName() throws Exception {
        DatabaseConnectionInfo config = oracle();
        Assume.assumeNotNull("缺少 Oracle 连接环境变量，跳过", config);

        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            DatabaseMetadataService service = new DatabaseMetadataService();
            String[][] cases = {
                    {"%", null},
                    {"S%", PROBE_SCHEMA},
                    {"%FLOAT%", PROBE_SCHEMA},
                    {PROBE_TABLE, null},
                    {PROBE_TABLE, PROBE_SCHEMA},
            };
            for (String[] c : cases) {
                int hit = service.listTables(provider, new MetadataQuery(null, c[1], c[0])).size();
                System.out.println("  [HIT] pattern=" + c[0] + " schema=" + c[1] + " -> " + hit);
                assertTrue("模式 " + c[0] + " 应至少命中 1 张表，实际 " + hit, hit >= 1);
            }
        }
    }

    /** C7 端到端：Oracle BINARY_FLOAT / BINARY_DOUBLE → PostgreSQL，核对落地类型与精度。 */
    @Test
    public void migratesOracleFloatToPostgres() throws Exception {
        DatabaseConnectionInfo source = oracle();
        DatabaseConnectionInfo target = postgres();
        Assume.assumeNotNull("缺少 Oracle 连接环境变量，跳过", source);
        Assume.assumeNotNull("缺少 PostgreSQL 连接环境变量，跳过", target);

        try (JdbcDriverLoader loader = new JdbcDriverLoader()) {
            EtlResult result = EtlTask.builder()
                    .source(new JdbcTableSource(source, PROBE_TABLE, loader, 10))
                    .target(new JdbcTableTarget(target, "sl_float_probe", loader, MetadataErrorPolicy.FAIL))
                    .targetMode(TargetMode.REPLACE).build().run();
            assertSuccess("Oracle→PostgreSQL", result);
            Map<String, Object> row = firstRow(target, "sl_float_probe");
            assertFloatRow("PG", row);
        }
    }

    /** C7 端到端：Oracle BINARY_FLOAT / BINARY_DOUBLE → 达梦，核对落地类型与精度。 */
    @Test
    public void migratesOracleFloatToDm() throws Exception {
        DatabaseConnectionInfo source = oracle();
        DatabaseConnectionInfo target = dm();
        Assume.assumeNotNull("缺少 Oracle 连接环境变量，跳过", source);
        Assume.assumeNotNull("缺少达梦连接环境变量，跳过", target);

        try (JdbcDriverLoader loader = new JdbcDriverLoader()) {
            EtlResult result = EtlTask.builder()
                    .source(new JdbcTableSource(source, PROBE_TABLE, loader, 10))
                    .target(new JdbcTableTarget(target, "SL_FLOAT_PROBE", loader, MetadataErrorPolicy.FAIL))
                    .targetMode(TargetMode.REPLACE).build().run();
            assertSuccess("Oracle→达梦", result);
            // 达梦用 schema 区分源/目标，读取必须带限定名，否则会命中同名的历史残留表
            Map<String, Object> row = firstRow(target, "SL_TARGET.SL_FLOAT_PROBE");
            assertFloatRow("DM", row);
        }
    }

    /** 迁移成功、且源表 1 行全部写入。 */
    private static void assertSuccess(String label, EtlResult result) {
        for (EtlError error : result.getErrors())
            System.out.println("  [ERR-" + label + "] stage=" + error.getStage() + " type=" + error.getType()
                    + " msg=" + error.getMessage());
        System.out.println("  [ETL-" + label + "] status=" + result.getStatus()
                + " read=" + result.getRead() + " transformed=" + result.getTransformed()
                + " filtered=" + result.getFiltered() + " written=" + result.getWritten()
                + " failed=" + result.getFailed() + " errors=" + result.getErrors().size()
                + " elapsedMs=" + result.getElapsedMillis());
        assertEquals(label + " 迁移状态", "SUCCESS", String.valueOf(result.getStatus()));
        assertEquals(label + " 写入行数", 1L, result.getWritten());
    }

    /** BF 必须是单精度值、BD 必须保留双精度全部有效位。 */
    private static void assertFloatRow(String label, Map<String, Object> row) {
        for (Map.Entry<String, Object> entry : row.entrySet())
            System.out.println("  [DATA-" + label + "] " + entry.getKey() + " = " + entry.getValue()
                    + " (" + (entry.getValue() == null ? "null" : entry.getValue().getClass().getSimpleName()) + ")");
        Object bf = row.get("BF");
        Object bd = row.get("BD");
        assertTrue(label + " BF 应为单精度数值，实际 " + bf, bf instanceof Number);
        assertTrue(label + " BD 应为双精度数值，实际 " + bd, bd instanceof Number);
        assertEquals(label + " BF 单精度取值", 1.2345679f, ((Number) bf).floatValue(), 1e-6);
        assertEquals(label + " BD 双精度取值", 1.23456789012345d, ((Number) bd).doubleValue(), 1e-12);
    }

    /** 读取目标表首行，列名统一大写便于断言。 */
    private static Map<String, Object> firstRow(DatabaseConnectionInfo config, String table) throws Exception {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config);
             Connection connection = provider.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM " + table)) {
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            if (!rs.next()) return row;
            for (int i = 1; i <= meta.getColumnCount(); i++)
                row.put(meta.getColumnLabel(i).toUpperCase(java.util.Locale.ENGLISH), rs.getObject(i));
        }
        return row;
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
                OracleVendorTypeProbeIntegrationTest.class.getClassLoader(),
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

    /** Oracle 探针表连接配置，任一必需环境变量缺失时返回 null。 */
    private static DatabaseConnectionInfo oracle() {
        String user = env("SCHEMALOOM_IT_ORACLE_USER", null);
        String password = env("SCHEMALOOM_IT_ORACLE_PASSWORD", null);
        if (user == null || password == null) return null;
        return new DatabaseConnectionInfo(DatabaseType.ORACLE,
                env("SCHEMALOOM_IT_ORACLE_HOST", "localhost"),
                Integer.parseInt(env("SCHEMALOOM_IT_ORACLE_PORT", "1521")),
                env("SCHEMALOOM_IT_ORACLE_DATABASE", "FREEPDB1"),
                user, password,
                env("SCHEMALOOM_IT_ORACLE_DRIVER_ID", "oracle23"), null);
    }

    /** PostgreSQL 目标连接配置，缺少用户/口令时返回 null。 */
    private static DatabaseConnectionInfo postgres() {
        String user = env("SCHEMALOOM_IT_POSTGRESQL_USER", null);
        String password = env("SCHEMALOOM_IT_POSTGRESQL_PASSWORD", null);
        if (user == null || password == null) return null;
        return new DatabaseConnectionInfo(DatabaseType.POSTGRESQL,
                env("SCHEMALOOM_IT_POSTGRESQL_HOST", "localhost"),
                Integer.parseInt(env("SCHEMALOOM_IT_POSTGRESQL_PORT", "15432")),
                env("SCHEMALOOM_IT_POSTGRESQL_DATABASE", "sl_target"),
                user, password,
                env("SCHEMALOOM_IT_POSTGRESQL_DRIVER_ID", "postgresql16"), null);
    }

    /** 达梦目标连接配置，缺少用户/口令时返回 null。 */
    private static DatabaseConnectionInfo dm() {
        String user = env("SCHEMALOOM_IT_DM_USER", null);
        String password = env("SCHEMALOOM_IT_DM_PASSWORD", null);
        if (user == null || password == null) return null;
        return new DatabaseConnectionInfo(DatabaseType.DM,
                env("SCHEMALOOM_IT_DM_HOST", "localhost"),
                Integer.parseInt(env("SCHEMALOOM_IT_DM_PORT", "15236")),
                env("SCHEMALOOM_IT_DM_DATABASE", "SYSDBA"),
                null, env("SCHEMALOOM_IT_DM_SCHEMA", "SL_TARGET"),
                user, password,
                env("SCHEMALOOM_IT_DM_DRIVER_ID", "dm"), null);
    }

    /** SQL Server 连接配置，缺少用户/口令时返回 null。 */
    private static DatabaseConnectionInfo sqlServer() {
        String user = env("SCHEMALOOM_IT_SQLSERVER_USER", null);
        String password = env("SCHEMALOOM_IT_SQLSERVER_PASSWORD", null);
        if (user == null || password == null) return null;
        return new DatabaseConnectionInfo(DatabaseType.SQL_SERVER,
                env("SCHEMALOOM_IT_SQLSERVER_HOST", "localhost"),
                Integer.parseInt(env("SCHEMALOOM_IT_SQLSERVER_PORT", "11433")),
                env("SCHEMALOOM_IT_SQLSERVER_DATABASE", "master"),
                user, password,
                env("SCHEMALOOM_IT_SQLSERVER_DRIVER_ID", "sqlserver2022"), null);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
