package io.github.nameof.schemaloom.integration;

import io.github.nameof.schemaloom.api.EtlError;
import io.github.nameof.schemaloom.api.EtlResult;
import io.github.nameof.schemaloom.api.EtlStatus;
import io.github.nameof.schemaloom.api.FieldSchema;
import io.github.nameof.schemaloom.api.LogicalType;
import io.github.nameof.schemaloom.api.LogicalTypeCatalog;
import io.github.nameof.schemaloom.api.MetadataErrorPolicy;
import io.github.nameof.schemaloom.api.TargetMode;
import io.github.nameof.schemaloom.codec.JdbcValueCodec;
import io.github.nameof.schemaloom.dialect.DatabaseDialect;
import io.github.nameof.schemaloom.dialect.DialectRegistry;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.driver.DatabaseType;
import io.github.nameof.schemaloom.driver.JdbcDriverLoader;
import io.github.nameof.schemaloom.engine.EtlTask;
import io.github.nameof.schemaloom.metadata.DatabaseMetadataService;
import io.github.nameof.schemaloom.metadata.IndexInfo;
import io.github.nameof.schemaloom.metadata.MetadataQuery;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.metadata.TableInfo;
import io.github.nameof.schemaloom.source.JdbcTableSource;
import io.github.nameof.schemaloom.target.JdbcTableTarget;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.time.OffsetDateTime;
import java.time.OffsetTime;

import static org.junit.Assert.*;

/**
 * 真实容器上的方言行为探针。
 * 目的：用真实数据库验证 B5 / B6 / B7 的推断，而不是停留在语法规范推导上。
 * 输出为诊断信息，断言在结论确认后再补进对应的集成测试。
 */
public class DialectBehaviorProbeIntegrationTest {

    /** 执行 SQL，返回是否成功；失败只记录，交由调用方决定是否断言。 */
    private static boolean exec(String label, Connection c, String sql) {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
            System.out.println("[OK]   " + label + "  ->  " + sql);
            return true;
        } catch (SQLException e) {
            System.out.println("[FAIL] " + label + "  ->  " + sql);
            System.out.println("       SQLState=" + e.getSQLState() + " msg="
                    + String.valueOf(e.getMessage()).split("\n")[0]);
            return false;
        }
    }

    private static void query(String label, Connection c, String sql) {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            StringBuilder sb = new StringBuilder();
            while (r.next()) sb.append(r.getString(1)).append(" | ");
            System.out.println("[Q]    " + label + "  ->  " + sb);
        } catch (SQLException e) {
            System.out.println("[QF]   " + label + "  ->  "
                    + String.valueOf(e.getMessage()).split("\n")[0]);
        }
    }

    // ===== B5：DB2 标识符大小写 =====
    @Test
    public void probesDb2IdentifierCase() throws Exception {
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.DB2, "localhost", 15000,
                "BLUDB", null, "DB2INST1", "db2inst1", "db2inst1", "db2", null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            Connection c = provider.getConnection();
            DatabaseDialect dialect = new DialectRegistry().get(DatabaseType.DB2);

            exec("清理", c, "DROP TABLE \"mytable\"");
            exec("清理", c, "DROP TABLE mytable2");
            // 加引号创建的小写标识符：DB2 按字面存储，区分大小写
            exec("加引号建小写表", c, "CREATE TABLE \"mytable\" (ID INT)");
            // 不加引号创建：DB2 自动转大写存储
            exec("不加引号建小写表", c, "CREATE TABLE mytable2 (ID INT)");

            query("系统表实际存储的表名", c,
                    "SELECT TABNAME FROM SYSCAT.TABLES WHERE TABSCHEMA='DB2INST1' AND TABNAME LIKE 'MYTABLE%'");

            // 当前 Db2Dialect.quote() 强制大写后生成的形态
            // B5 回归：方言 quote 出来的形态必须能命中库里已存在的加引号小写对象。
            // 修复前 quote() 强制大写输出 "MYTABLE"，DB2 报 SQLCODE=-204。
            String dialectForm = dialect.quote("mytable");
            assertEquals("DB2 方言应原样保留标识符大小写", "\"mytable\"", dialectForm);
            assertTrue("DB2 方言形态应能命中加引号创建的小写表",
                    exec("方言形态 " + dialectForm, c, "SELECT COUNT(*) FROM " + dialectForm));
            exec("原样 \"mytable\"", c, "SELECT COUNT(*) FROM \"mytable\"");
            // 不加引号创建的表按大写存储，用大写名能命中
            exec("不加引号表的 \"MYTABLE2\"", c, "SELECT COUNT(*) FROM \"MYTABLE2\"");
        }
    }

    // ===== B5：达梦标识符大小写 =====
    @Test
    public void probesDmIdentifierCase() throws Exception {
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.DM, "localhost", 15236,
                "SYSDBA", null, "SL_B5", "SYSDBA", "Sysdba001", "dm", null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            Connection c = provider.getConnection();
            DatabaseDialect dialect = new DialectRegistry().get(DatabaseType.DM);

            exec("建 schema", c, "CREATE SCHEMA SL_B5");
            exec("清理", c, "DROP TABLE SL_B5.\"mytable\"");
            exec("清理", c, "DROP TABLE SL_B5.mytable2");
            exec("加引号建小写表", c, "CREATE TABLE SL_B5.\"mytable\" (ID INT)");
            exec("不加引号建小写表", c, "CREATE TABLE SL_B5.mytable2 (ID INT)");

            query("字典里实际存储的表名", c,
                    "SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER='SL_B5'");

            // B5 回归：修复前 quote() 强制大写，SL_B5."MYTABLE" 报对象不存在
            String dialectForm = dialect.quote("mytable");
            assertEquals("达梦方言应原样保留标识符大小写", "\"mytable\"", dialectForm);
            assertTrue("达梦方言形态应能命中加引号创建的小写表",
                    exec("方言形态 SL_B5." + dialectForm, c, "SELECT COUNT(*) FROM SL_B5." + dialectForm));
            exec("原样 SL_B5.\"mytable\"", c, "SELECT COUNT(*) FROM SL_B5.\"mytable\"");
            exec("不加引号表的 SL_B5.\"MYTABLE2\"", c, "SELECT COUNT(*) FROM SL_B5.\"MYTABLE2\"");
        }
    }

    // ===== B6：达梦 SET IDENTITY_INSERT 是否接受完整限定名 =====
    @Test
    public void probesDmIdentityInsert() throws Exception {
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.DM, "localhost", 15236,
                "SYSDBA", null, "SL_B6", "SYSDBA", "Sysdba001", "dm", null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            Connection c = provider.getConnection();
            DatabaseDialect dialect = new DialectRegistry().get(DatabaseType.DM);

            exec("建 schema", c, "CREATE SCHEMA SL_B6");
            exec("清理", c, "DROP TABLE SL_B6.T1");
            exec("建带 IDENTITY 的表", c,
                    "CREATE TABLE SL_B6.T1 (ID INT IDENTITY(1,1) PRIMARY KEY, NAME VARCHAR(20))");

            // B6 回归：实测达梦接受完整限定名（带引号与否均可），反而不接受仅表名。
            // 当前 DmDialect 生成的形态1 是正确的，这里断言它必须继续可用。
            String form1On = dialect.identityInsertOnSql(dialect.quote(new QualifiedTableName(null, "SL_B6", "T1")));
            String form1Off = dialect.identityInsertOffSql(dialect.quote(new QualifiedTableName(null, "SL_B6", "T1")));
            System.out.println("--- 形态1（当前代码生成）: " + form1On);
            assertTrue("达梦应接受完整限定名的 SET IDENTITY_INSERT", exec("形态1 ON", c, form1On));
            assertTrue("开启后应能插入显式 ID",
                    exec("形态1 插入显式ID", c, "INSERT INTO SL_B6.T1 (ID, NAME) VALUES (100, 'quoted_qualified')"));
            exec("形态1 OFF", c, form1Off);

            // 形态 2：不带引号的限定名
            System.out.println("--- 形态2: SET IDENTITY_INSERT SL_B6.T1 ON");
            exec("形态2 ON", c, "SET IDENTITY_INSERT SL_B6.T1 ON");
            exec("形态2 插入显式ID", c, "INSERT INTO SL_B6.T1 (ID, NAME) VALUES (200, 'plain_qualified')");
            exec("形态2 OFF", c, "SET IDENTITY_INSERT SL_B6.T1 OFF");

            // 形态 3：只给表名
            System.out.println("--- 形态3: SET IDENTITY_INSERT T1 ON");
            exec("形态3 ON", c, "SET IDENTITY_INSERT T1 ON");
            exec("形态3 插入显式ID", c, "INSERT INTO SL_B6.T1 (ID, NAME) VALUES (300, 'bare_table')");
            exec("形态3 OFF", c, "SET IDENTITY_INSERT T1 OFF");

            query("最终表内容", c, "SELECT ID || '-' || NAME FROM SL_B6.T1 ORDER BY ID");
        }
    }

    // ===== B5 回归：rename 的大小写必须与 quote() 一致（DB2 / 达梦 REPLACE 全链路）=====
    @Test
    public void probesReplaceRenameCaseConsistency() throws Exception {
        // DB2
        DatabaseConnectionInfo db2 = new DatabaseConnectionInfo(DatabaseType.DB2, "localhost", 15000,
                "BLUDB", null, "DB2INST1", "db2inst1", "db2inst1", "db2", null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader()) {
            try (ConnectionProvider setup = loader.connect(db2)) {
                Connection c = setup.getConnection();
                exec("DB2 清理源", c, "DROP TABLE SL_RN_SRC");
                exec("DB2 清理目标", c, "DROP TABLE SL_RN_TGT");
                exec("DB2 清理临时表", c, "DROP TABLE SL_RN_TGT_tmp");
                exec("DB2 建源", c, "CREATE TABLE SL_RN_SRC (ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(20))");
                exec("DB2 源数据", c, "INSERT INTO SL_RN_SRC VALUES (1, 'alpha')");
                exec("DB2 建目标占位", c, "CREATE TABLE SL_RN_TGT (OLD_VALUE VARCHAR(10))");
            }
            EtlResult r = EtlTask.builder()
                    .source(new JdbcTableSource(db2, "SL_RN_SRC", loader, 1))
                    .target(new JdbcTableTarget(db2, "SL_RN_TGT", loader, MetadataErrorPolicy.FAIL))
                    .targetMode(TargetMode.REPLACE).build().run();
            System.out.println("[RN-DB2] REPLACE -> status=" + r.getStatus() + " written=" + r.getWritten());
            for (EtlError error : r.getErrors())
                System.out.println("         RN-DB2 error stage=" + error.getStage() + " msg="
                        + String.valueOf(error.getMessage()).replace("\n", " | "));
            assertEquals("DB2 REPLACE 必须成功（rename 大小写不一致会失败）", EtlStatus.SUCCESS, r.getStatus());
            try (ConnectionProvider check = loader.connect(db2)) {
                query("DB2 目标内容", check.getConnection(), "SELECT ID || '-' || NAME FROM SL_RN_TGT");
            }
        }
    }

    // ===== A3 诊断：达梦 REPLACE 的 rename 为什么失败 =====
    @Test
    public void probesDmReplaceRenameFailure() throws Exception {
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.DM, "localhost", 15236,
                "SYSDBA", null, "SL_B9", "SYSDBA", "Sysdba001", "dm", null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            Connection c = provider.getConnection();
            DatabaseDialect dialect = new DialectRegistry().get(DatabaseType.DM);
            exec("建 schema", c, "CREATE SCHEMA SL_B9");

            // 还原 REPLACE 的形态：临时表（含 IDENTITY）+ 原表
            exec("清理临时表", c, "DROP TABLE SL_B9.RN_TMP");
            exec("清理原表", c, "DROP TABLE SL_B9.RN");
            exec("建临时表（含 IDENTITY）", c,
                    "CREATE TABLE SL_B9.RN_TMP (ID INT IDENTITY(1,1) PRIMARY KEY, NAME VARCHAR(20))");
            exec("建原表", c, "CREATE TABLE SL_B9.RN (OLD_VALUE VARCHAR(10))");

            String renameSql = dialect.renameTableSql(
                    dialect.quote(new QualifiedTableName(null, "SL_B9", "RN_TMP")),
                    dialect.quote(new QualifiedTableName(null, "SL_B9", "RN")));
            // 形态0：完全复刻项目 REPLACE 的每一步（用源表元数据建临时表 -> 写入 -> rename）
            TableInfo src = new DatabaseMetadataService().getTable(provider, config.table("SRC_A"));
            QualifiedTableName tmp = new QualifiedTableName(null, "SL_B9", "RN0_TMP");
            String createSql = dialect.createTableSql(dialect.quote(tmp), src);
            System.out.println("[DIAG] createTableSql = " + createSql.replace("\n", " "));
            exec("形态0 清理临时表", c, "DROP TABLE SL_B9.RN0_TMP");
            exec("形态0 清理目标", c, "DROP TABLE SL_B9.RN0");
            if (exec("形态0 建临时表", c, createSql)) {
                System.out.println("[DIAG] 源表索引 = " + src.getIndexes().size());
                for (IndexInfo index : src.getIndexes())
                    System.out.println("[DIAG]   index name=" + index.getName() + " unique=" + index.isUnique()
                            + " cols=" + index.getColumns());
                for (String s : dialect.commentSql(dialect.quote(tmp), src)) exec("形态0 注释", c, s);
                for (String s : dialect.indexSql(dialect.quote(tmp), src.getIndexes())) exec("形态0 索引", c, s);
                exec("形态0 identity on", c, dialect.identityInsertOnSql(dialect.quote(tmp)));
                exec("形态0 插入", c, "INSERT INTO SL_B9.RN0_TMP (ID, NAME) VALUES (1, 'x')");
                exec("形态0 identity off", c, dialect.identityInsertOffSql(dialect.quote(tmp)));
                exec("形态0 rename", c, dialect.renameTableSql(dialect.quote(tmp),
                        dialect.quote(new QualifiedTableName(null, "SL_B9", "RN0"))));
                query("形态0 目标是否存在", c, "SELECT COUNT(*) FROM SL_B9.RN0");
            }

            System.out.println("[DIAG] 方言生成的 rename SQL = " + renameSql);
            exec("drop 原表", c, "DROP TABLE SL_B9.RN");
            System.out.println("[DIAG] 形态1（方言生成）");
            exec("形态1 rename", c, renameSql);
            query("形态1 后是否存在 RN", c, "SELECT COUNT(*) FROM SL_B9.RN");

            // 形态2：新旧名字都不带引号
            exec("清理", c, "DROP TABLE SL_B9.RN");
            exec("重建临时表", c,
                    "CREATE TABLE SL_B9.RN_TMP (ID INT IDENTITY(1,1) PRIMARY KEY, NAME VARCHAR(20))");
            System.out.println("[DIAG] 形态2 不带引号");
            exec("形态2 rename", c, "ALTER TABLE SL_B9.RN_TMP RENAME TO RN");

            // 形态3：新名带引号大写
            exec("清理", c, "DROP TABLE SL_B9.RN");
            exec("重建临时表", c,
                    "CREATE TABLE SL_B9.RN_TMP (ID INT IDENTITY(1,1) PRIMARY KEY, NAME VARCHAR(20))");
            System.out.println("[DIAG] 形态3 新名带引号");
            exec("形态3 rename", c, "ALTER TABLE SL_B9.RN_TMP RENAME TO \"RN\"");
            query("最终 RN 是否存在", c, "SELECT COUNT(*) FROM SL_B9.RN");
        }
    }

    // ===== C2：Oracle BOOLEAN 落到 CHAR(1) 后的读写绑定 =====
    @Test
    public void probesOracleBooleanBinding() throws Exception {
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.ORACLE, "localhost", 1521,
                "FREEPDB1", null, "SCHEMALOOM", "SCHEMALOOM", "Oracle_IT_2026", "oracle23", null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            Connection c = provider.getConnection();
            DatabaseDialect dialect = new DialectRegistry().get(DatabaseType.ORACLE);
            FieldSchema flag = new FieldSchema("flag", LogicalType.BOOLEAN, true, null, null, null);
            System.out.println("--- Oracle BOOLEAN 的 DDL 类型: "
                    + dialect.mapping(LogicalType.BOOLEAN).getDdlType(flag));

            String ddlType = dialect.mapping(LogicalType.BOOLEAN).getDdlType(flag);
            exec("清理", c, "DROP TABLE SL_C2");
            assertTrue("建表失败", exec("建表（BOOLEAN 落到 " + ddlType + "）", c,
                    "CREATE TABLE SL_C2 (ID NUMBER(10), FLAG " + ddlType + ")"));

            // C2 回归：修复前 DDL 是 CHAR(1)，setBoolean 触发 ORA-12899（值太大）
            assertTrue("项目路径写入 true 必须成功", bind(c, "形态1 项目路径 true", flag, dialect, Boolean.TRUE, 1));
            assertTrue("项目路径写入 false 必须成功", bind(c, "形态2 项目路径 false", flag, dialect, Boolean.FALSE, 2));
            assertTrue("项目路径写入 null 必须成功", bind(c, "形态3 项目路径 null", flag, dialect, null, 3));

            query("实际存储值", c, "SELECT ID || '=' || '[' || TO_CHAR(FLAG) || ']' FROM SL_C2 ORDER BY ID");

            // C2 回归：读回必须得到 true / false / null
            List<String> readBack = new ArrayList<String>();
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT FLAG FROM SL_C2 ORDER BY ID")) {
                while (r.next()) readBack.add(String.valueOf(LogicalTypeCatalog.get(LogicalType.BOOLEAN).readJdbc(r, 1)));
            }
            System.out.println("[Q]    读回序列 -> " + readBack);
            assertEquals(Arrays.asList("true", "false", "null"), readBack);
        }
    }

    /**
     * 枚举 Oracle 上 BOOLEAN 的可行落地组合：DDL 类型 × 写入绑定 × 读回方式。
     * 目的：实测哪种组合在真实 Oracle 上能把布尔值存进去又读回来。
     */
    @Test
    public void probesOracleBooleanVariants() throws Exception {
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.ORACLE, "localhost", 1521,
                "FREEPDB1", null, "SCHEMALOOM", "SCHEMALOOM", "Oracle_IT_2026", "oracle23", null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            Connection c = provider.getConnection();
            query("Oracle 版本", c, "SELECT BANNER FROM V$VERSION WHERE ROWNUM = 1");

            String[] ddlTypes = {"CHAR(1)", "VARCHAR2(5)", "NUMBER(1)", "BOOLEAN"};
            for (String ddl : ddlTypes) {
                String table = "SL_C2V_" + ddl.replaceAll("[^A-Z0-9]", "");
                exec("清理 " + ddl, c, "DROP TABLE " + table);
                if (!exec("建表 " + ddl, c, "CREATE TABLE " + table + " (ID NUMBER(10), FLAG " + ddl + ")")) continue;

                // 写入：setObject(Boolean) 走驱动原生布尔绑定
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (ID, FLAG) VALUES (?, ?)")) {
                    ps.setInt(1, 1);
                    ps.setObject(2, Boolean.TRUE);
                    ps.executeUpdate();
                    System.out.println("[OK]   " + ddl + " setObject(Boolean.TRUE)");
                } catch (Exception e) {
                    System.out.println("[FAIL] " + ddl + " setObject(Boolean.TRUE) -> "
                            + String.valueOf(e.getMessage()).split("\n")[0]);
                }
                // 写入：按字符串 '1'
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (ID, FLAG) VALUES (?, ?)")) {
                    ps.setInt(1, 2);
                    ps.setString(2, "1");
                    ps.executeUpdate();
                    System.out.println("[OK]   " + ddl + " setString('1')");
                } catch (Exception e) {
                    System.out.println("[FAIL] " + ddl + " setString('1') -> "
                            + String.valueOf(e.getMessage()).split("\n")[0]);
                }
                query(ddl + " 存储值", c, "SELECT ID || '=' || '[' || TO_CHAR(FLAG) || ']' FROM " + table + " ORDER BY ID");

                // 读回：项目用 getObject(Boolean.class)
                try (Statement s = c.createStatement();
                     ResultSet r = s.executeQuery("SELECT FLAG FROM " + table + " ORDER BY ID")) {
                    while (r.next()) {
                        try {
                            Object v = LogicalTypeCatalog.get(LogicalType.BOOLEAN).readJdbc(r, 1);
                            System.out.println("[OK]   " + ddl + " readJdbc -> " + v);
                        } catch (Exception e) {
                            System.out.println("[FAIL] " + ddl + " readJdbc -> "
                                    + String.valueOf(e.getMessage()).split("\n")[0]);
                        }
                    }
                }
            }
        }
    }

    private static boolean bind(Connection c, String label, FieldSchema field, DatabaseDialect dialect,
                                Object value, int id) {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO SL_C2 (ID, FLAG) VALUES (?, ?)")) {
            ps.setInt(1, id);
            JdbcValueCodec.write(ps, 2, field, value, dialect);
            ps.executeUpdate();
            System.out.println("[OK]   " + label + " -> 成功");
            return true;
        } catch (Exception e) {
            System.out.println("[FAIL] " + label + " -> " + String.valueOf(e.getMessage()).split("\n")[0]);
            return false;
        }
    }

    // ===== B9：IDENTITY_INSERT 的判定依据是源表还是目标表 =====
    @Test
    public void probesDmIdentityInsertTargetMismatch() throws Exception {
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.DM, "localhost", 15236,
                "SYSDBA", null, "SL_B9", "SYSDBA", "Sysdba001", "dm", null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            Connection c = provider.getConnection();
            exec("建 schema", c, "CREATE SCHEMA SL_B9");

            // ---------- 场景 A：源表有自增列，目标表没有 ----------
            exec("A 清理源", c, "DROP TABLE SL_B9.SRC_A");
            exec("A 清理目标", c, "DROP TABLE SL_B9.TGT_A");
            exec("A 建源（含 IDENTITY）", c,
                    "CREATE TABLE SL_B9.SRC_A (ID INT IDENTITY(1,1) PRIMARY KEY, NAME VARCHAR(20))");
            exec("A 源插入", c, "INSERT INTO SL_B9.SRC_A (NAME) VALUES ('a')");
            exec("A 建目标（无 IDENTITY）", c, "CREATE TABLE SL_B9.TGT_A (ID INT, NAME VARCHAR(20))");

            EtlResult a = EtlTask.builder()
                    .source(new JdbcTableSource(config, "SRC_A", loader, 1))
                    .target(new JdbcTableTarget(config, "TGT_A", loader, MetadataErrorPolicy.FAIL))
                    .targetMode(TargetMode.APPEND).build().run();
            System.out.println("[B9-A] 源有自增/目标无自增 APPEND -> status=" + a.getStatus()
                    + " written=" + a.getWritten());
            for (EtlError error : a.getErrors())
                System.out.println("       B9-A error stage=" + error.getStage() + " msg="
                        + String.valueOf(error.getMessage()).split("\n")[0]);
            // B9 回归：修复前会对没有自增列的目标表执行 SET IDENTITY_INSERT，整个批次失败
            assertEquals("源表有自增列、目标表没有时，不应开启 IDENTITY_INSERT",
                    EtlStatus.SUCCESS, a.getStatus());
            assertEquals(1, a.getWritten());
            query("A 目标行数", c, "SELECT COUNT(*) FROM SL_B9.TGT_A");

            // ---------- 场景 A2：目标表也有自增列，仍需开启 IDENTITY_INSERT（防回归）----------
            exec("A2 清理目标", c, "DROP TABLE SL_B9.TGT_A2");
            exec("A2 建目标（含 IDENTITY）", c,
                    "CREATE TABLE SL_B9.TGT_A2 (ID INT IDENTITY(1,1) PRIMARY KEY, NAME VARCHAR(20))");
            EtlResult a2 = EtlTask.builder()
                    .source(new JdbcTableSource(config, "SRC_A", loader, 1))
                    .target(new JdbcTableTarget(config, "TGT_A2", loader, MetadataErrorPolicy.FAIL))
                    .targetMode(TargetMode.APPEND).build().run();
            System.out.println("[B9-A2] 双方都有自增列 APPEND -> status=" + a2.getStatus()
                    + " written=" + a2.getWritten());
            for (EtlError error : a2.getErrors())
                System.out.println("       B9-A2 error stage=" + error.getStage() + " msg="
                        + String.valueOf(error.getMessage()).split("\n")[0]);
            assertEquals("目标表有自增列时仍必须开启 IDENTITY_INSERT", EtlStatus.SUCCESS, a2.getStatus());
            query("A2 目标内容", c, "SELECT ID || '-' || NAME FROM SL_B9.TGT_A2 ORDER BY ID");

            // ---------- 场景 A3：REPLACE 全流程（临时表由源表结构新建，仍需 IDENTITY_INSERT）----------
            exec("A3 清理目标", c, "DROP TABLE SL_B9.TGT_A3");
            exec("A3 建目标（占位）", c, "CREATE TABLE SL_B9.TGT_A3 (OLD_VALUE VARCHAR(10))");
            EtlResult a3 = EtlTask.builder()
                    .source(new JdbcTableSource(config, "SRC_A", loader, 1))
                    .target(new JdbcTableTarget(config, "TGT_A3", loader, MetadataErrorPolicy.FAIL))
                    .targetMode(TargetMode.REPLACE).build().run();
            System.out.println("[B9-A3] REPLACE -> status=" + a3.getStatus() + " written=" + a3.getWritten());
            for (EtlError error : a3.getErrors())
                System.out.println("       B9-A3 error stage=" + error.getStage() + " msg="
                        + String.valueOf(error.getMessage()).replace("\n", " | "));
            assertEquals("REPLACE 模式不应受 B9 修复影响", EtlStatus.SUCCESS, a3.getStatus());
            query("A3 目标内容", c, "SELECT ID || '-' || NAME FROM SL_B9.TGT_A3 ORDER BY ID");

            // ---------- 场景 B：源表无自增列，目标表有 ----------
            exec("B 清理源", c, "DROP TABLE SL_B9.SRC_B");
            exec("B 清理目标", c, "DROP TABLE SL_B9.TGT_B");
            exec("B 建源（无 IDENTITY）", c, "CREATE TABLE SL_B9.SRC_B (ID INT, NAME VARCHAR(20))");
            exec("B 源插入显式 ID", c, "INSERT INTO SL_B9.SRC_B (ID, NAME) VALUES (500, 'b')");
            exec("B 建目标（含 IDENTITY）", c,
                    "CREATE TABLE SL_B9.TGT_B (ID INT IDENTITY(1,1) PRIMARY KEY, NAME VARCHAR(20))");

            EtlResult b = EtlTask.builder()
                    .source(new JdbcTableSource(config, "SRC_B", loader, 1))
                    .target(new JdbcTableTarget(config, "TGT_B", loader, MetadataErrorPolicy.FAIL))
                    .targetMode(TargetMode.APPEND).build().run();
            System.out.println("[B9-B] 源无自增/目标有自增 APPEND -> status=" + b.getStatus()
                    + " written=" + b.getWritten());
            for (EtlError error : b.getErrors())
                System.out.println("       B9-B error stage=" + error.getStage() + " msg="
                        + String.valueOf(error.getMessage()).split("\n")[0]);
            query("B 目标内容", c, "SELECT ID || '-' || NAME FROM SL_B9.TGT_B");
        }
    }

    // ===== B8 + C1：OFFSET_* 降级后，绑定是否仍用带时区的 JDBC 类型码 =====
    @Test
    public void probesDb2OffsetTimeBinding() throws Exception {
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.DB2, "localhost", 15000,
                "BLUDB", null, "DB2INST1", "db2inst1", "db2inst1", "db2", null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            Connection c = provider.getConnection();
            DatabaseDialect dialect = new DialectRegistry().get(DatabaseType.DB2);

            // B8：确认方言确实把 OFFSET_* 降级成了无时区的类型
            System.out.println("[B8]   DB2 OFFSET_TIME -> "
                    + dialect.mapping(LogicalType.OFFSET_TIME).getDdlType(FieldSchema.of("ot", LogicalType.OFFSET_TIME)));
            System.out.println("[B8]   DB2 OFFSET_TIMESTAMP -> "
                    + dialect.mapping(LogicalType.OFFSET_TIMESTAMP).getDdlType(FieldSchema.of("ots", LogicalType.OFFSET_TIMESTAMP)));

            exec("清理", c, "DROP TABLE SL_C1");
            exec("建表（按降级后的 DDL 类型）", c, "CREATE TABLE SL_C1 (ID INT, OT TIME, OTS TIMESTAMP)");

            // 形态 1：当前代码的实际绑定（setObject + TIME_WITH_TIMEZONE）
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO SL_C1 (ID, OT, OTS) VALUES (?, ?, ?)")) {
                ps.setInt(1, 1);
                ps.setObject(2, OffsetTime.parse("10:11:12+08:00"), Types.TIME_WITH_TIMEZONE);
                ps.setObject(3, OffsetDateTime.parse("2026-01-02T10:11:12+08:00"), Types.TIMESTAMP_WITH_TIMEZONE);
                ps.executeUpdate();
                System.out.println("[OK]   C1 形态1 setObject(TIME_WITH_TIMEZONE) -> 成功");
            } catch (SQLException e) {
                System.out.println("[FAIL] C1 形态1 setObject(TIME_WITH_TIMEZONE) -> "
                        + String.valueOf(e.getMessage()).split("\n")[0]);
            }

            // 形态 2：写 null 时用带时区的类型码
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO SL_C1 (ID, OT, OTS) VALUES (?, ?, ?)")) {
                ps.setInt(1, 2);
                ps.setNull(2, Types.TIME_WITH_TIMEZONE);
                ps.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE);
                ps.executeUpdate();
                System.out.println("[OK]   C1 形态2 setNull(TIME_WITH_TIMEZONE) -> 成功");
            } catch (SQLException e) {
                System.out.println("[FAIL] C1 形态2 setNull(TIME_WITH_TIMEZONE) -> "
                        + String.valueOf(e.getMessage()).split("\n")[0]);
            }

            // 形态 3：跟随降级结果的绑定（按目标 DDL 类型 TIME / TIMESTAMP）
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO SL_C1 (ID, OT, OTS) VALUES (?, ?, ?)")) {
                ps.setInt(1, 3);
                ps.setTime(2, Time.valueOf("10:11:12"));
                ps.setTimestamp(3, Timestamp.valueOf("2026-01-02 10:11:12"));
                ps.executeUpdate();
                System.out.println("[OK]   C1 形态3 降级绑定 setTime/setTimestamp -> 成功");
            } catch (SQLException e) {
                System.out.println("[FAIL] C1 形态3 降级绑定 -> " + String.valueOf(e.getMessage()).split("\n")[0]);
            }

            // 形态 4：修复后走项目自己的写入路径（JdbcValueCodec 带方言），必须成功
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO SL_C1 (ID, OT, OTS) VALUES (?, ?, ?)")) {
                ps.setInt(1, 4);
                JdbcValueCodec.write(ps, 2, FieldSchema.of("ot", LogicalType.OFFSET_TIME),
                        OffsetTime.parse("10:11:12+08:00"), dialect);
                JdbcValueCodec.write(ps, 3, FieldSchema.of("ots", LogicalType.OFFSET_TIMESTAMP),
                        OffsetDateTime.parse("2026-01-02T10:11:12+08:00"), dialect);
                ps.executeUpdate();
                System.out.println("[OK]   C1 形态4 项目写入路径（绑定跟随降级）-> 成功");
            } catch (Exception e) {
                System.out.println("[FAIL] C1 形态4 项目写入路径 -> " + String.valueOf(e.getMessage()).split("\n")[0]);
            }

            // 形态 5：null 也走项目写入路径
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO SL_C1 (ID, OT, OTS) VALUES (?, ?, ?)")) {
                ps.setInt(1, 5);
                JdbcValueCodec.write(ps, 2, FieldSchema.of("ot", LogicalType.OFFSET_TIME), null, dialect);
                JdbcValueCodec.write(ps, 3, FieldSchema.of("ots", LogicalType.OFFSET_TIMESTAMP), null, dialect);
                ps.executeUpdate();
                System.out.println("[OK]   C1 形态5 项目写入路径（null）-> 成功");
            } catch (Exception e) {
                System.out.println("[FAIL] C1 形态5 null -> " + String.valueOf(e.getMessage()).split("\n")[0]);
            }
            query("最终写入", c, "SELECT ID || '/' || COALESCE(VARCHAR(OT),'NULL') FROM SL_C1 ORDER BY ID");
        }
    }

    // ===== B7：GBase 8a rename 语法 =====
    @Test
    public void probesGbaseRenameSyntax() throws Exception {
        String password = System.getenv("SCHEMALOOM_IT_GBASE_PASSWORD");
        if (password == null) password = "GBase_IT_2026";
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.GBASE, "localhost", 15258,
                "schemaloom_it", null, null, "schemaloom", password, "gbase8a", null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            Connection c = provider.getConnection();
            DatabaseDialect dialect = new DialectRegistry().get(DatabaseType.GBASE);

            exec("清理", c, "DROP TABLE IF EXISTS sl_rename_src");
            exec("清理", c, "DROP TABLE IF EXISTS sl_rename_dst");
            exec("清理", c, "DROP TABLE IF EXISTS sl_rename_tmp");
            exec("建表", c, "CREATE TABLE sl_rename_src (ID INT, NAME VARCHAR(20))");

            // 形态 1：当前 GBase8aDialect 生成的 ALTER TABLE ... RENAME TO（B7 回归）
            String form1 = dialect.renameTableSql("sl_rename_src", "sl_rename_dst");
            System.out.println("--- 形态1（当前代码生成）: " + form1);
            assertTrue("GBase 8a 必须支持 ALTER TABLE ... RENAME TO（当前实现）",
                    exec("形态1 ALTER RENAME TO", c, form1));

            // 形态 2：GBase 8a 手册里的 RENAME TABLE a TO b
            System.out.println("--- 形态2: RENAME TABLE sl_rename_dst TO sl_rename_tmp");
            assertTrue("GBase 8a 必须支持 RENAME TABLE ... TO",
                    exec("形态2 RENAME TABLE", c, "RENAME TABLE sl_rename_dst TO sl_rename_tmp"));

            query("最终存在的表", c, "SHOW TABLES LIKE 'sl_rename%'");
        }
    }
}
