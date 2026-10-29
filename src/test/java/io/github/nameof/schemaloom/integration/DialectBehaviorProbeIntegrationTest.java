package io.github.nameof.schemaloom.integration;

import io.github.nameof.schemaloom.api.FieldSchema;
import io.github.nameof.schemaloom.api.LogicalType;
import io.github.nameof.schemaloom.codec.JdbcValueCodec;
import io.github.nameof.schemaloom.dialect.DatabaseDialect;
import io.github.nameof.schemaloom.dialect.DialectRegistry;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.driver.DatabaseType;
import io.github.nameof.schemaloom.driver.JdbcDriverLoader;
import io.github.nameof.schemaloom.metadata.DatabaseMetadataService;
import io.github.nameof.schemaloom.metadata.MetadataQuery;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.metadata.TableInfo;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
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
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.GBASE, "localhost", 15258,
                "schemaloom_it", null, null, "schemaloom", System.getenv("SCHEMALOOM_IT_GBASE_PASSWORD"),
                "gbase8a", null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            Connection c = provider.getConnection();
            DatabaseDialect dialect = new DialectRegistry().get(DatabaseType.GBASE);

            exec("清理", c, "DROP TABLE IF EXISTS sl_rename_src");
            exec("清理", c, "DROP TABLE IF EXISTS sl_rename_dst");
            exec("清理", c, "DROP TABLE IF EXISTS sl_rename_tmp");
            exec("建表", c, "CREATE TABLE sl_rename_src (ID INT, NAME VARCHAR(20))");

            // 形态 1：当前 GBase8aDialect 生成的 ALTER TABLE ... RENAME TO
            String form1 = dialect.renameTableSql("sl_rename_src", "sl_rename_dst");
            System.out.println("--- 形态1（当前代码生成）: " + form1);
            exec("形态1 ALTER RENAME TO", c, form1);

            // 形态 2：GBase 8a 官方的 RENAME TABLE a TO b
            System.out.println("--- 形态2: RENAME TABLE sl_rename_dst TO sl_rename_tmp");
            exec("形态2 RENAME TABLE", c, "RENAME TABLE sl_rename_dst TO sl_rename_tmp");

            query("最终存在的表", c, "SHOW TABLES LIKE 'sl_rename%'");
        }
    }
}
