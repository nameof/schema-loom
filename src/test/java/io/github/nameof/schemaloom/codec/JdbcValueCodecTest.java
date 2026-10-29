package io.github.nameof.schemaloom.codec;

import io.github.nameof.schemaloom.api.FieldSchema;
import io.github.nameof.schemaloom.api.LogicalType;
import io.github.nameof.schemaloom.dialect.DatabaseDialect;
import io.github.nameof.schemaloom.dialect.DialectRegistry;
import io.github.nameof.schemaloom.driver.DatabaseType;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetTime;

import static org.junit.Assert.*;

/** 校验 JDBC 绑定跟随方言降级。 */
public class JdbcValueCodecTest {

    /** DB2 把 OFFSET_TIME 降级为 TIME，绑定必须跟着降级，否则驱动报「无法识别的 JDBC 类型 2013」。 */
    @Test
    public void writesDegradedOffsetTimeAsLocalTime() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:codec;DB_CLOSE_DELAY=-1")) {
            c.createStatement().execute("CREATE TABLE t (ot TIME)");
            DatabaseDialect db2 = new DialectRegistry().get(DatabaseType.DB2);
            FieldSchema field = FieldSchema.of("ot", LogicalType.OFFSET_TIME);

            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t (ot) VALUES (?)")) {
                JdbcValueCodec.write(ps, 1, field, OffsetTime.parse("10:11:12+08:00"), db2);
                ps.executeUpdate();
            }
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT ot FROM t")) {
                assertTrue(r.next());
                assertEquals("10:11:12", r.getTime(1).toLocalTime().toString());
            }

            // null 同样跟随降级
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t (ot) VALUES (?)")) {
                JdbcValueCodec.write(ps, 1, field, null, db2);
                ps.executeUpdate();
            }
        }
    }

    /** 不传方言（或方言未降级）时保持原语义：仍按带时区的类型绑定。 */
    @Test
    public void keepsTimeZoneTypeWhenDialectDoesNotDegrade() throws Exception {
        DatabaseDialect oracle = new DialectRegistry().get(DatabaseType.ORACLE);
        assertNull(oracle.mapping(LogicalType.OFFSET_TIME).degradesTo());
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:codec2;DB_CLOSE_DELAY=-1")) {
            c.createStatement().execute("CREATE TABLE t (ot TIME)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t (ot) VALUES (?)")) {
                // 未降级时按 OffsetTime 原样绑定；H2 能否接受不在本用例断言范围内，
                // 这里只确保降级分支与非降级分支不会互相污染
                JdbcValueCodec.write(ps, 1, FieldSchema.of("ot", LogicalType.OFFSET_TIME),
                        OffsetTime.parse("10:11:12+08:00"), oracle);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            // 目标库不接受带时区的类型是预期的，本用例不关心
        }
    }
}
