package io.github.nameof.schemaloom.integration;

import io.github.nameof.schemaloom.api.DataRecord;
import io.github.nameof.schemaloom.api.EtlError;
import io.github.nameof.schemaloom.api.EtlResult;
import io.github.nameof.schemaloom.api.MetadataErrorPolicy;
import io.github.nameof.schemaloom.api.TargetMode;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.driver.DatabaseType;
import io.github.nameof.schemaloom.driver.JdbcDriverLoader;
import io.github.nameof.schemaloom.engine.EtlTask;
import io.github.nameof.schemaloom.metadata.ColumnInfo;
import io.github.nameof.schemaloom.metadata.DatabaseMetadataService;
import io.github.nameof.schemaloom.metadata.TableInfo;
import io.github.nameof.schemaloom.source.JdbcTableSource;
import io.github.nameof.schemaloom.target.JdbcTableTarget;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * C3 / C5 / C6 的真机探针（只做诊断输出，结论确认后再落成断言）。
 *
 * <p>C3：各库对 FLOAT / REAL 上报的 JDBC 类型码，以及双精度列被当成 FLOAT32 读是否会丢精度。</p>
 * <p>C5：{@code ResultSet#getObject(int, Class)} 在各驱动上是否真的可用。</p>
 * <p>C6：null 绑定沿用源列 JDBC 类型码时，跨库写入是否失败。</p>
 */
public class TypeBindingProbeIntegrationTest {

    private static final String PASSWORD = "SchemaLoom_IT_2026!";

    static DatabaseConnectionInfo sqlServer() {
        return new DatabaseConnectionInfo(DatabaseType.SQL_SERVER, "localhost", 11433, "master", null, "dbo", "sa", PASSWORD, "sqlserver2022", null);
    }

    static DatabaseConnectionInfo db2() {
        return new DatabaseConnectionInfo(DatabaseType.DB2, "localhost", 15000, "BLUDB", null, "DB2INST1", "db2inst1", "db2inst1", "db2", null);
    }

    static DatabaseConnectionInfo dm() {
        return new DatabaseConnectionInfo(DatabaseType.DM, "localhost", 15236, "SYSDBA", null, "SYSDBA", "SYSDBA", "Sysdba001", "dm", null);
    }

    static DatabaseConnectionInfo postgres() {
        return new DatabaseConnectionInfo(DatabaseType.POSTGRESQL, "localhost", 15432, "schemaloom_it", null, null, "schemaloom", PASSWORD, "postgresql16", null);
    }

    static DatabaseConnectionInfo gbase() {
        return new DatabaseConnectionInfo(DatabaseType.GBASE, "localhost", 15258, "schemaloom_it", null, null, "schemaloom", "GBase_IT_2026", "gbase8a", null);
    }

    static DatabaseConnectionInfo oracle() {
        return new DatabaseConnectionInfo(DatabaseType.ORACLE, "localhost", 1521, "FREEPDB1", null, "SCHEMALOOM", "SCHEMALOOM", "Oracle_IT_2026", "oracle23", null);
    }

    private static void exec(Connection c, String sql) {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (Exception e) {
            System.out.println("  [SQL-FAIL] " + sql + " -> " + e.getMessage());
        }
    }

    static void dumpColumns(DatabaseConnectionInfo config, JdbcDriverLoader loader, String table) {
        try (ConnectionProvider provider = loader.connect(config)) {
            TableInfo info = new DatabaseMetadataService().getTable(provider, config.table(table));
            for (ColumnInfo column : info.getColumns())
                System.out.println("  [COL] " + config.getDatabaseType() + "." + table + " " + column.getName()
                        + " typeName=" + column.getTypeName()
                        + " jdbcType=" + column.getJdbcType()
                        + " logical=" + column.getLogicalType()
                        + " precision=" + column.getPrecision());
        } catch (Exception e) {
            System.out.println("  [TABLE-FAIL] " + config.getDatabaseType() + "." + table + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ===== C3：FLOAT / REAL / DOUBLE 的实际类型码 =====
    @Test
    public void probesFloatTypeMapping() {
        System.out.println("===== C3 FLOAT 映射探针 =====");
        try (JdbcDriverLoader loader = new JdbcDriverLoader()) {
            try (ConnectionProvider provider = loader.connect(sqlServer())) {
                Connection c = provider.getConnection();
                exec(c, "IF OBJECT_ID('dbo.sl_float_probe','U') IS NOT NULL DROP TABLE dbo.sl_float_probe");
                exec(c, "CREATE TABLE dbo.sl_float_probe (f_generic float, f_24 float(24), f_53 float(53), r real)");
            }
            dumpColumns(sqlServer(), loader, "sl_float_probe");

            try (ConnectionProvider provider = loader.connect(db2())) {
                Connection c = provider.getConnection();
                exec(c, "DROP TABLE SL_FLOAT_PROBE");
                exec(c, "CREATE TABLE SL_FLOAT_PROBE (F_GENERIC FLOAT, F_REAL REAL, F_DOUBLE DOUBLE)");
            }
            dumpColumns(db2(), loader, "SL_FLOAT_PROBE");

            try (ConnectionProvider provider = loader.connect(dm())) {
                Connection c = provider.getConnection();
                exec(c, "DROP TABLE SL_FLOAT_PROBE");
                exec(c, "CREATE TABLE SL_FLOAT_PROBE (F_GENERIC FLOAT, F_REAL REAL, F_DOUBLE DOUBLE)");
            }
            dumpColumns(dm(), loader, "SL_FLOAT_PROBE");

            try (ConnectionProvider provider = loader.connect(postgres())) {
                Connection c = provider.getConnection();
                exec(c, "DROP TABLE IF EXISTS sl_float_probe");
                exec(c, "CREATE TABLE sl_float_probe (f_generic float, f_real real, f_double double precision)");
            }
            dumpColumns(postgres(), loader, "sl_float_probe");

            try (ConnectionProvider provider = loader.connect(gbase())) {
                Connection c = provider.getConnection();
                exec(c, "DROP TABLE sl_float_probe");
                exec(c, "CREATE TABLE sl_float_probe (f_generic float, f_double double)");
            }
            dumpColumns(gbase(), loader, "sl_float_probe");

            // Oracle：BINARY_FLOAT 是 4 字节、BINARY_DOUBLE 是 8 字节，用来确认 FLOAT 类型码不会被误改
            try (ConnectionProvider provider = loader.connect(oracle())) {
                Connection c = provider.getConnection();
                exec(c, "DROP TABLE SL_FLOAT_PROBE");
                exec(c, "CREATE TABLE SL_FLOAT_PROBE (F_GENERIC FLOAT, F_BIN_FLOAT BINARY_FLOAT, F_BIN_DOUBLE BINARY_DOUBLE)");
            }
            dumpColumns(oracle(), loader, "SL_FLOAT_PROBE");
        } catch (Exception e) {
            System.out.println("[C3-ERROR] " + e);
        }
    }

    /** C3 的关键验证：达梦 FLOAT 存高精度值，经 Schemaloom 读回是否被压成单精度。 */
    @Test
    public void probesPrecisionLoss() {
        System.out.println("===== C3 精度损失探针（达梦） =====");
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(dm())) {
            Connection c = provider.getConnection();
            exec(c, "DROP TABLE SL_PREC_PROBE");
            exec(c, "CREATE TABLE SL_PREC_PROBE (F FLOAT, R REAL, D DOUBLE)");
            // 12 位有效数字，单精度只能保留 7 位
            exec(c, "INSERT INTO SL_PREC_PROBE (F, R, D) VALUES (1.23456789012345, 1.23456789012345, 1.23456789012345)");
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT F, R, D FROM SL_PREC_PROBE")) {
                rs.next();
                System.out.println("  [RAW] getDouble(F)=" + rs.getDouble(1) + " getFloat(F)=" + rs.getFloat(1)
                        + " getString(F)=" + rs.getString(1));
            }
            try (JdbcTableSource source = new JdbcTableSource(dm(), "SL_PREC_PROBE", loader, 10)) {
                List<DataRecord> records = source.preview(10);
                for (DataRecord record : records)
                    for (int i = 0; i < record.getSchema().getFields().size(); i++)
                        System.out.println("  [READ] " + record.getSchema().getFields().get(i).getName()
                                + " logical=" + record.getSchema().getFields().get(i).getLogicalType()
                                + " value=" + record.get(i) + " class=" + record.get(i).getClass().getName());
            }
        } catch (Throwable e) {
            System.out.println("[C3-PREC-ERROR] " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    // ===== C5：ResultSet#getObject(int, Class) 是否被驱动实现 =====
    @Test
    public void probesTypedGetObject() {
        System.out.println("===== C5 typed getObject 探针 =====");
        probeTypedGetObject("SQLSERVER", sqlServer());
        probeTypedGetObject("DB2", db2());
        probeTypedGetObject("DM", dm());
        probeTypedGetObject("POSTGRES", postgres());
        probeTypedGetObject("GBASE", gbase());
        probeTypedGetObject("ORACLE", oracle());
    }

    private void probeTypedGetObject(String label, DatabaseConnectionInfo config) {
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config);
             Statement s = provider.getConnection().createStatement()) {
            System.out.println("  [RS] " + label + " conn=" + provider.getConnection().getClass().getName());
            s.execute("CREATE TABLE sl_c5_probe (n INT, d DECIMAL(10,2))");
            s.execute("INSERT INTO sl_c5_probe VALUES (7, 1.25)");
            try (ResultSet rs = s.executeQuery("SELECT n, d, 1 FROM sl_c5_probe")) {
                rs.next();
                System.out.println("  [RS] " + label + " rsImpl=" + rs.getClass().getName());
                try {
                    Object value = rs.getObject(1, Integer.class);
                    System.out.println("  [RS] " + label + " getObject(int, Integer.class) = " + value);
                } catch (Throwable t) {
                    System.out.println("  [RS] " + label + " getObject(int, Class) 失败 -> " + t.getClass().getName() + ": " + t.getMessage());
                }
                try {
                    Object value = rs.getObject(2, java.math.BigDecimal.class);
                    System.out.println("  [RS] " + label + " getObject(int, BigDecimal.class) = " + value);
                } catch (Throwable t) {
                    System.out.println("  [RS] " + label + " getObject(int, BigDecimal.class) 失败 -> " + t.getClass().getName() + ": " + t.getMessage());
                }
                System.out.println("  [RS] " + label + " getInt=" + rs.getInt(1) + " getBigDecimal=" + rs.getBigDecimal(2));
            }
            s.execute("DROP TABLE sl_c5_probe");
        } catch (Throwable e) {
            System.out.println("  [RS] " + label + " 连接/执行失败 -> " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    // ===== C6：null 绑定沿用源列 JDBC 类型码，跨库是否失败 =====
    @Test
    public void probesNullBindingAcrossDatabases() {
        System.out.println("===== C6 null 绑定探针 =====");
        try (JdbcDriverLoader loader = new JdbcDriverLoader()) {
            // 源：SQL Server 的 bit 列（JDBC 类型码 -7 / BIT），逻辑类型 BOOLEAN
            DatabaseConnectionInfo source = sqlServer();
            DatabaseConnectionInfo target = postgres();
            try (ConnectionProvider provider = loader.connect(source)) {
                Connection c = provider.getConnection();
                exec(c, "IF OBJECT_ID('dbo.sl_null_src','U') IS NOT NULL DROP TABLE dbo.sl_null_src");
                exec(c, "CREATE TABLE dbo.sl_null_src (id int, flag bit null)");
                exec(c, "INSERT INTO dbo.sl_null_src (id, flag) VALUES (1, 1)");
                exec(c, "INSERT INTO dbo.sl_null_src (id, flag) VALUES (2, NULL)");
            }
            try (ConnectionProvider provider = loader.connect(target)) {
                Connection c = provider.getConnection();
                exec(c, "DROP TABLE IF EXISTS sl_null_tgt");
                exec(c, "CREATE TABLE sl_null_tgt (id int, flag boolean)");
            }
            dumpColumns(source, loader, "sl_null_src");
            try (ConnectionProvider provider = loader.connect(target)) {
                int found = new DatabaseMetadataService().listTables(provider,
                        io.github.nameof.schemaloom.metadata.MetadataQuery.fromConnectionInfo(
                                target, new io.github.nameof.schemaloom.metadata.QualifiedTableName(null, null, "sl_null_tgt"))).size();
                System.out.println("  [C6] 目标表元数据命中数=" + found);
            }
            try {
                EtlResult result = EtlTask.builder()
                        .source(new JdbcTableSource(source, "sl_null_src", loader, 2))
                        .target(new JdbcTableTarget(target, "sl_null_tgt", loader, MetadataErrorPolicy.FAIL))
                        .targetMode(TargetMode.APPEND).build().run();
                System.out.println("  [C6] status=" + result.getStatus() + " read=" + result.getRead() + " written=" + result.getWritten());
                for (EtlError error : result.getErrors())
                    System.out.println("  [C6] error stage=" + error.getStage() + " type=" + error.getType() + " msg=" + error.getMessage());
            } catch (Throwable e) {
                System.out.println("  [C6] 任务抛出 " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace(System.out);
            }
        }
    }

    /** 诊断：E3 把 database 下推为 catalog 后，各库的元数据命中是否还正常。 */
    @Test
    public void probeMetadataScopes() {
        System.out.println("===== 元数据作用域诊断 =====");
        scope("SQLSERVER", sqlServer(), "sl_float_probe");
        scope("DB2", db2(), "SL_FLOAT_PROBE");
        scope("DM", dm(), "SL_FLOAT_PROBE");
        scope("POSTGRES", postgres(), "sl_null_tgt");
        scope("GBASE", gbase(), "sl_float_probe");
        scope("ORACLE", oracle(), "SL_FLOAT_PROBE");
    }

    private void scope(String label, DatabaseConnectionInfo config, String table) {
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            DatabaseMetadataService service = new DatabaseMetadataService();
            int pushed = service.listTables(provider,
                    io.github.nameof.schemaloom.metadata.MetadataQuery.fromConnectionInfo(config,
                            new io.github.nameof.schemaloom.metadata.QualifiedTableName(null, null, table))).size();
            int plain = service.listTables(provider,
                    new io.github.nameof.schemaloom.metadata.MetadataQuery(null, null, table)).size();
            int all = service.listTables(provider,
                    new io.github.nameof.schemaloom.metadata.MetadataQuery(null, null, "%")).size();
            System.out.println("  [SCOPE] " + label + " fromConnectionInfo=" + pushed + " 不过滤=" + plain
                    + " 全库=" + all
                    + " database=" + config.getDatabase() + " catalog=" + config.getCatalog() + " schema=" + config.getSchema());
        } catch (Throwable e) {
            System.out.println("  [SCOPE] " + label + " 失败 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** C6 变体：源列 JDBC 类型码在目标库没有对应类型时，null 绑定是否会失败。 */
    @Test
    public void probesNullBindingExotic() {
        System.out.println("===== C6 冷门类型 null 绑定 =====");
        try (JdbcDriverLoader loader = new JdbcDriverLoader()) {
            DatabaseConnectionInfo source = postgres();
            DatabaseConnectionInfo target = sqlServer();
            try (ConnectionProvider provider = loader.connect(source)) {
                Connection c = provider.getConnection();
                exec(c, "DROP TABLE IF EXISTS sl_exotic_src");
                exec(c, "CREATE TABLE sl_exotic_src (id int, u uuid, j jsonb, t timestamptz, iv interval)");
                exec(c, "INSERT INTO sl_exotic_src (id) VALUES (1)");
            }
            try (ConnectionProvider provider = loader.connect(target)) {
                Connection c = provider.getConnection();
                // 目标表不预建：让 Schemaloom 按源 Schema 建表，null 绑定仍沿用源列类型码
                exec(c, "IF OBJECT_ID('dbo.sl_exotic_tgt','U') IS NOT NULL DROP TABLE dbo.sl_exotic_tgt");
            }
            dumpColumns(source, loader, "sl_exotic_src");
            runQuietly(loader, source, "sl_exotic_src", target, "sl_exotic_tgt", "C6X");
        } catch (Throwable e) {
            System.out.println("  [C6X] 外层失败 " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private void runQuietly(JdbcDriverLoader loader, DatabaseConnectionInfo source, String sourceTable,
                            DatabaseConnectionInfo target, String targetTable, String tag) {
        try {
            EtlResult result = EtlTask.builder()
                    .source(new JdbcTableSource(source, sourceTable, loader, 2))
                    .target(new JdbcTableTarget(target, targetTable, loader, MetadataErrorPolicy.FAIL))
                    .targetMode(TargetMode.APPEND).build().run();
            System.out.println("  [" + tag + "] status=" + result.getStatus() + " written=" + result.getWritten());
            for (EtlError error : result.getErrors())
                System.out.println("  [" + tag + "] error stage=" + error.getStage() + " type=" + error.getType() + " msg=" + error.getMessage());
        } catch (Throwable e) {
            System.out.println("  [" + tag + "] 任务抛出 " + e.getClass().getName() + ": " + e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                System.out.println("  [" + tag + "]   caused by " + cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
            }
        }
    }

    /** C6 矩阵：最严的 DB2 / Oracle 作为目标，源列类型码与目标列类型不一致时 null 绑定是否失败。 */
    @Test
    public void probesNullBindingMatrix() {
        System.out.println("===== C6 null 绑定矩阵 =====");
        try (JdbcDriverLoader loader = new JdbcDriverLoader()) {
            DatabaseConnectionInfo sqlSource = sqlServer();
            DatabaseConnectionInfo pgSource = postgres();
            try (ConnectionProvider provider = loader.connect(sqlSource)) {
                Connection c = provider.getConnection();
                exec(c, "IF OBJECT_ID('dbo.sl_nb','U') IS NOT NULL DROP TABLE dbo.sl_nb");
                exec(c, "CREATE TABLE dbo.sl_nb (id int, flag bit, nv nvarchar(20), ts datetime2, bin varbinary(8))");
                exec(c, "INSERT INTO dbo.sl_nb (id) VALUES (1)");
            }
            try (ConnectionProvider provider = loader.connect(pgSource)) {
                Connection c = provider.getConnection();
                exec(c, "DROP TABLE IF EXISTS sl_nb_pg");
                exec(c, "CREATE TABLE sl_nb_pg (id int, u uuid, j jsonb)");
                exec(c, "INSERT INTO sl_nb_pg (id) VALUES (1)");
            }
            dumpColumns(sqlSource, loader, "sl_nb");
            dumpColumns(pgSource, loader, "sl_nb_pg");
            for (DatabaseConnectionInfo target : new DatabaseConnectionInfo[]{db2(), oracle()}) {
                cleanup(loader, target, "SL_NB_TGT");
                runQuietly(loader, sqlSource, "sl_nb", target, "SL_NB_TGT", "C6M-SQLSERVER->" + target.getDatabaseType());
                cleanup(loader, target, "SL_NB_PGTGT");
                runQuietly(loader, pgSource, "sl_nb_pg", target, "SL_NB_PGTGT", "C6M-POSTGRES->" + target.getDatabaseType());
            }
        } catch (Throwable e) {
            System.out.println("  [C6M] 外层失败 " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private void cleanup(JdbcDriverLoader loader, DatabaseConnectionInfo config, String table) {
        try (ConnectionProvider provider = loader.connect(config)) {
            exec(provider.getConnection(), "DROP TABLE " + table);
        } catch (Throwable ignored) {
        }
    }

    /** 最小复现：目标库列类型固定，直接用源列的 JDBC 类型码 setNull 是否会失败。 */
    @Test
    public void probesSetNullTypeCodes() {
        System.out.println("===== C6 setNull 类型码最小复现 =====");
        setNull("ORACLE", oracle(), "sl_sn_probe", "CREATE TABLE sl_sn_probe (id INT, v VARCHAR2(50))", "INSERT INTO sl_sn_probe (id, v) VALUES (?, ?)");
        setNull("DB2", db2(), "SL_SN_PROBE", "CREATE TABLE SL_SN_PROBE (ID INT, V VARCHAR(50))", "INSERT INTO SL_SN_PROBE (ID, V) VALUES (?, ?)");
        setNull("SQLSERVER", sqlServer(), "dbo.sl_sn_probe", "CREATE TABLE dbo.sl_sn_probe (id INT, v NVARCHAR(50))", "INSERT INTO dbo.sl_sn_probe (id, v) VALUES (?, ?)");
        setNull("POSTGRES", postgres(), "sl_sn_probe", "CREATE TABLE sl_sn_probe (id INT, v VARCHAR(50))", "INSERT INTO sl_sn_probe (id, v) VALUES (?, ?)");
        setNull("DM", dm(), "SL_SN_PROBE", "CREATE TABLE SL_SN_PROBE (ID INT, V VARCHAR(50))", "INSERT INTO SL_SN_PROBE (ID, V) VALUES (?, ?)");
    }

    private void setNull(String label, DatabaseConnectionInfo config, String table, String ddl, String insert) {
        int[] codes = {1111 /*OTHER*/, -7 /*BIT*/, 2013 /*TIME_WITH_TIMEZONE*/, 2014 /*TIMESTAMP_WITH_TIMEZONE*/, -9 /*NVARCHAR*/, -3 /*VARBINARY*/};
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            Connection c = provider.getConnection();
            exec(c, "DROP TABLE " + table);
            exec(c, ddl);
            for (int code : codes) {
                try (java.sql.PreparedStatement ps = c.prepareStatement(insert)) {
                    ps.setInt(1, code);
                    ps.setNull(2, code);
                    ps.executeUpdate();
                    System.out.println("  [SN] " + label + " setNull(" + code + ") OK");
                } catch (Throwable t) {
                    System.out.println("  [SN] " + label + " setNull(" + code + ") 失败 -> " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).split("\n")[0]);
                }
            }
        } catch (Throwable e) {
            System.out.println("  [SN] " + label + " 连接失败 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 逻辑类型标准 JDBC 码在各目标库的 setNull 接受度（决定 null 绑定该用哪个码）。 */
    @Test
    public void probesSetNullLogicalCodes() {
        System.out.println("===== C6 逻辑类型标准码接受度 =====");
        int[] codes = {16 /*BOOLEAN*/, 5 /*SMALLINT*/, 4 /*INTEGER*/, -5 /*BIGINT*/, 3 /*DECIMAL*/, 7 /*REAL*/, 8 /*DOUBLE*/,
                12 /*VARCHAR*/, 91 /*DATE*/, 92 /*TIME*/, 93 /*TIMESTAMP*/, -2 /*BINARY*/, 2013 /*TIME_TZ*/, 2014 /*TIMESTAMP_TZ*/};
        accepted("ORACLE", oracle(), "sl_code_probe", "CREATE TABLE sl_code_probe (v VARCHAR2(50))", codes);
        accepted("DB2", db2(), "SL_CODE_PROBE", "CREATE TABLE SL_CODE_PROBE (V VARCHAR(50))", codes);
        accepted("SQLSERVER", sqlServer(), "dbo.sl_code_probe", "CREATE TABLE dbo.sl_code_probe (v NVARCHAR(50))", codes);
        accepted("POSTGRES", postgres(), "sl_code_probe", "CREATE TABLE sl_code_probe (v VARCHAR(50))", codes);
        accepted("DM", dm(), "SL_CODE_PROBE", "CREATE TABLE SL_CODE_PROBE (V VARCHAR(50))", codes);
        accepted("GBASE", gbase(), "sl_code_probe", "CREATE TABLE sl_code_probe (v VARCHAR(50))", codes);
    }

    private void accepted(String label, DatabaseConnectionInfo config, String table, String ddl, int[] codes) {
        StringBuilder ok = new StringBuilder();
        StringBuilder bad = new StringBuilder();
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            Connection c = provider.getConnection();
            exec(c, "DROP TABLE " + table);
            exec(c, ddl);
            for (int code : codes) {
                try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (v) VALUES (?)")) {
                    ps.setNull(1, code);
                    ps.executeUpdate();
                    ok.append(code).append(' ');
                } catch (Throwable t) {
                    bad.append(code).append('(').append(t.getClass().getSimpleName()).append(") ");
                }
            }
        } catch (Throwable e) {
            System.out.println("  [CODE] " + label + " 连接失败 " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        System.out.println("  [CODE] " + label + " OK=[" + ok.toString().trim() + "] FAIL=[" + bad.toString().trim() + "]");
    }

    /** 绕过 SchemaCrawler，直接看驱动的 DATA_TYPE，确认 Oracle BINARY_FLOAT 的类型码。 */
    @Test
    public void probeRawJdbcTypes() {
        System.out.println("===== 原始 JDBC DATA_TYPE =====");
        rawTypes("ORACLE", oracle(), "SCHEMALOOM", "SL_FLOAT_PROBE");
        rawTypes("DB2", db2(), "DB2INST1", "SL_FLOAT_PROBE");
    }

    private void rawTypes(String label, DatabaseConnectionInfo config, String schema, String table) {
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            Connection c = provider.getConnection();
            java.sql.DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getColumns(null, schema, table, "%")) {
                while (rs.next())
                    System.out.println("  [RAWJDBC] " + label + " " + rs.getString("COLUMN_NAME")
                            + " DATA_TYPE=" + rs.getInt("DATA_TYPE")
                            + " TYPE_NAME=" + rs.getString("TYPE_NAME")
                            + " COLUMN_SIZE=" + rs.getObject("COLUMN_SIZE"));
            }
        } catch (Throwable e) {
            System.out.println("  [RAWJDBC] " + label + " 失败 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * C6 回归：null 绑定必须按目标方言语义取类型码。
     * 修复前 —— PostgreSQL 源的 uuid（1111）写到 Oracle 报 ORA-17004，SQL Server 源的
     * nvarchar（-9）写到 PostgreSQL 报「无法确定参数类型」。修复后两条链路都应成功。
     */
    @Test
    public void verifiesNullBindingAcrossDatabases() {
        System.out.println("===== C6 回归断言 =====");
        try (JdbcDriverLoader loader = new JdbcDriverLoader()) {
            DatabaseConnectionInfo pgSource = postgres();
            DatabaseConnectionInfo sqlSource = sqlServer();
            try (ConnectionProvider provider = loader.connect(pgSource)) {
                Connection c = provider.getConnection();
                exec(c, "DROP TABLE IF EXISTS sl_c6_pg");
                exec(c, "CREATE TABLE sl_c6_pg (id int, u uuid, j jsonb)");
                exec(c, "INSERT INTO sl_c6_pg (id, u, j) VALUES (1, '6f1a3c1e-0000-0000-0000-000000000000', NULL)");
                exec(c, "INSERT INTO sl_c6_pg (id, u, j) VALUES (2, NULL, NULL)");
            }
            try (ConnectionProvider provider = loader.connect(sqlSource)) {
                Connection c = provider.getConnection();
                exec(c, "IF OBJECT_ID('dbo.sl_c6_ss','U') IS NOT NULL DROP TABLE dbo.sl_c6_ss");
                exec(c, "CREATE TABLE dbo.sl_c6_ss (id int, nv nvarchar(20))");
                exec(c, "INSERT INTO dbo.sl_c6_ss (id, nv) VALUES (1, N'alpha')");
                exec(c, "INSERT INTO dbo.sl_c6_ss (id, nv) VALUES (2, NULL)");
            }

            // PostgreSQL 源 → Oracle 目标（源列类型码 1111 在 Oracle 非法）
            cleanup(loader, oracle(), "SL_C6_ORACLE");
            EtlResult toOracle = EtlTask.builder()
                    .source(new JdbcTableSource(pgSource, "sl_c6_pg", loader, 2))
                    .target(new JdbcTableTarget(oracle(), "SL_C6_ORACLE", loader, MetadataErrorPolicy.FAIL))
                    .targetMode(TargetMode.APPEND).build().run();
            System.out.println("  [C6-ASSERT] PG->ORACLE status=" + toOracle.getStatus() + " written=" + toOracle.getWritten());
            for (EtlError error : toOracle.getErrors())
                System.out.println("  [C6-ASSERT]   error stage=" + error.getStage() + " msg=" + error.getMessage());
            org.junit.Assert.assertEquals(io.github.nameof.schemaloom.api.EtlStatus.SUCCESS, toOracle.getStatus());
            org.junit.Assert.assertEquals(2, toOracle.getWritten());

            // SQL Server 源 → PostgreSQL 目标（源列类型码 -9 在 PostgreSQL 非法）
            try (ConnectionProvider provider = loader.connect(pgSource)) {
                exec(provider.getConnection(), "DROP TABLE IF EXISTS sl_c6_pg_tgt");
            }
            EtlResult toPg = EtlTask.builder()
                    .source(new JdbcTableSource(sqlSource, "sl_c6_ss", loader, 2))
                    .target(new JdbcTableTarget(pgSource, "sl_c6_pg_tgt", loader, MetadataErrorPolicy.FAIL))
                    .targetMode(TargetMode.APPEND).build().run();
            System.out.println("  [C6-ASSERT] SQLSERVER->PG status=" + toPg.getStatus() + " written=" + toPg.getWritten());
            for (EtlError error : toPg.getErrors())
                System.out.println("  [C6-ASSERT]   error stage=" + error.getStage() + " msg=" + error.getMessage());
            org.junit.Assert.assertEquals(io.github.nameof.schemaloom.api.EtlStatus.SUCCESS, toPg.getStatus());
            org.junit.Assert.assertEquals(2, toPg.getWritten());
        } catch (Throwable e) {
            System.out.println("  [C6-ASSERT] 外层失败 " + e.getClass().getName() + ": " + e.getMessage());
            throw new AssertionError(e);
        }
    }

    /** C5 关键路径：BOOLEAN 列走的是 getObject(index, Boolean.class)，逐库确认。 */
    @Test
    public void probesTypedGetObjectBoolean() {
        System.out.println("===== C5 Boolean 型 getObject 探针 =====");
        booleanGet("ORACLE", oracle(), "sl_b_probe", "CREATE TABLE sl_b_probe (v NUMBER(1))", "INSERT INTO sl_b_probe (v) VALUES (1)");
        booleanGet("DB2", db2(), "SL_B_PROBE", "CREATE TABLE SL_B_PROBE (V SMALLINT)", "INSERT INTO SL_B_PROBE (V) VALUES (1)");
        booleanGet("SQLSERVER", sqlServer(), "dbo.sl_b_probe", "CREATE TABLE dbo.sl_b_probe (v BIT)", "INSERT INTO dbo.sl_b_probe (v) VALUES (1)");
        booleanGet("POSTGRES", postgres(), "sl_b_probe", "CREATE TABLE sl_b_probe (v BOOLEAN)", "INSERT INTO sl_b_probe (v) VALUES (true)");
        booleanGet("DM", dm(), "SL_B_PROBE", "CREATE TABLE SL_B_PROBE (V TINYINT)", "INSERT INTO SL_B_PROBE (V) VALUES (1)");
        booleanGet("GBASE", gbase(), "sl_b_probe", "CREATE TABLE sl_b_probe (v TINYINT)", "INSERT INTO sl_b_probe (v) VALUES (1)");
    }

    private void booleanGet(String label, DatabaseConnectionInfo config, String table, String ddl, String insert) {
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            Connection c = provider.getConnection();
            exec(c, "DROP TABLE " + table);
            exec(c, ddl);
            exec(c, insert);
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT v FROM " + table)) {
                rs.next();
                try {
                    Object value = rs.getObject(1, Boolean.class);
                    System.out.println("  [BOOL] " + label + " getObject(int, Boolean.class) = " + value);
                } catch (Throwable t) {
                    System.out.println("  [BOOL] " + label + " 失败 -> " + t.getClass().getName() + ": " + t.getMessage());
                }
                try {
                    System.out.println("  [BOOL] " + label + " getBoolean=" + rs.getBoolean(1));
                } catch (Throwable t) {
                    System.out.println("  [BOOL] " + label + " getBoolean 失败 -> " + t.getClass().getName() + ": " + t.getMessage());
                }
            }
        } catch (Throwable e) {
            System.out.println("  [BOOL] " + label + " 连接失败 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 诊断：Oracle 按精确表名查询返回 0，但全库能拿到 12 张表，确认是模式匹配的问题。 */
    @Test
    public void probeOraclePatterns() {
        System.out.println("===== Oracle 表名模式诊断 =====");
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(oracle())) {
            DatabaseMetadataService service = new DatabaseMetadataService();
            for (String pattern : new String[]{"%", "SL%", "SL_FLOAT_PROBE", "sl_float_probe", "%FLOAT%"})
                System.out.println("  [ORA-PAT] pattern=" + pattern + " -> " + service.listTables(provider,
                        new io.github.nameof.schemaloom.metadata.MetadataQuery(null, null, pattern)).size());
            for (String schema : new String[]{"SCHEMALOOM", "schemaloom"})
                System.out.println("  [ORA-PAT] catalog=null schema=" + schema + " -> " + service.listTables(provider,
                        new io.github.nameof.schemaloom.metadata.MetadataQuery(null, schema, "SL_FLOAT_PROBE")).size());
        } catch (Throwable e) {
            System.out.println("  [ORA-PAT] 失败 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** ETL 测试沙盒自查：逐个库连接并读 user_info，确认给用户的连接信息可用。 */
    @Test
    public void probesEtlSandbox() {
        System.out.println("===== ETL 沙盒连接自查 =====");
        sandbox("SQLSERVER", new DatabaseConnectionInfo(DatabaseType.SQL_SERVER, "localhost", 11433, "sl_source", null, "dbo", "sa", PASSWORD, "sqlserver2022", null), "user_info");
        sandbox("POSTGRES", new DatabaseConnectionInfo(DatabaseType.POSTGRESQL, "localhost", 15432, "sl_source", null, null, "schemaloom", PASSWORD, "postgresql16", null), "user_info");
        sandbox("DB2", new DatabaseConnectionInfo(DatabaseType.DB2, "localhost", 15000, "BLUDB", null, null, "db2inst1", "db2inst1", "db2", null), "SL_SOURCE.USER_INFO");
        sandbox("DM", new DatabaseConnectionInfo(DatabaseType.DM, "localhost", 15236, "SYSDBA", null, null, "SYSDBA", "Sysdba001", "dm", null), "SL_SOURCE.USER_INFO");
        sandbox("ORACLE", new DatabaseConnectionInfo(DatabaseType.ORACLE, "localhost", 1521, "FREEPDB1", null, "SL_SOURCE", "sl_source", "Sl_IT_2026", "oracle23", null), "USER_INFO");
        sandbox("GBASE", new DatabaseConnectionInfo(DatabaseType.GBASE, "localhost", 15258, "sl_source", null, null, "schemaloom", "GBase_IT_2026", "gbase8a", null), "user_info");
        sandbox("MARIADB", new DatabaseConnectionInfo(DatabaseType.MARIADB, "localhost", 13306, "sl_source", null, null, "sl", "Sl_IT_2026", "mariadb114", null), "user_info");
    }

    private void sandbox(String label, DatabaseConnectionInfo config, String table) {
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            try (Statement s = provider.getConnection().createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                System.out.println("  [SANDBOX] " + label + " OK rows=" + rs.getInt(1));
            }
        } catch (Throwable e) {
            System.out.println("  [SANDBOX] " + label + " 失败 " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).split("\n")[0]);
        }
    }

    /** C6 变体：源列类型码与目标列不同的其它组合，看是否还有别的失败点。 */
    @Test
    public void probesNullBindingVariants() {
        System.out.println("===== C6 null 绑定变体 =====");
        try (JdbcDriverLoader loader = new JdbcDriverLoader()) {
            DatabaseConnectionInfo source = sqlServer();
            DatabaseConnectionInfo target = postgres();
            try (ConnectionProvider provider = loader.connect(source)) {
                Connection c = provider.getConnection();
                exec(c, "IF OBJECT_ID('dbo.sl_null_var','U') IS NOT NULL DROP TABLE dbo.sl_null_var");
                exec(c, "CREATE TABLE dbo.sl_null_var (id int, nv nvarchar(20), dt date, tm time, ts datetime2, bin varbinary(8))");
                exec(c, "INSERT INTO dbo.sl_null_var (id) VALUES (1)");
            }
            try (ConnectionProvider provider = loader.connect(target)) {
                Connection c = provider.getConnection();
                exec(c, "DROP TABLE IF EXISTS sl_null_var");
                exec(c, "CREATE TABLE sl_null_var (id int, nv varchar(20), dt date, tm time, ts timestamp, bin bytea)");
            }
            dumpColumns(source, loader, "sl_null_var");
            try {
                EtlResult result = EtlTask.builder()
                        .source(new JdbcTableSource(source, "sl_null_var", loader, 2))
                        .target(new JdbcTableTarget(target, "sl_null_var", loader, MetadataErrorPolicy.FAIL))
                        .targetMode(TargetMode.APPEND).build().run();
                System.out.println("  [C6V] status=" + result.getStatus() + " written=" + result.getWritten());
                for (EtlError error : result.getErrors())
                    System.out.println("  [C6V] error stage=" + error.getStage() + " type=" + error.getType() + " msg=" + error.getMessage());
            } catch (Throwable e) {
                System.out.println("  [C6V] 任务抛出 " + e.getClass().getName() + ": " + e.getMessage());
            }
        }
    }
}
