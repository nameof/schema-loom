package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.LogicalType;
import org.junit.Test;

import java.sql.Types;

import static org.junit.Assert.assertEquals;

/** JDBC 类型码到逻辑类型的映射，重点覆盖浮点精度（C3）。 */
public class JdbcTypesTest {

    @Test
    public void mapsSinglePrecisionToFloat32() {
        assertEquals(LogicalType.FLOAT32, JdbcTypes.logical(Types.REAL));
    }

    /**
     * Types.FLOAT 必须是 FLOAT64：JDBC 规范里 FLOAT 对应 double，
     * 而 Oracle 的 FLOAT 与达梦的 FLOAT 都是双精度且上报的就是 Types.FLOAT。
     * 曾经按 FLOAT32 处理，达梦 FLOAT 存 1.23456789012345 读回只剩 1.2345679。
     */
    @Test
    public void mapsFloatToFloat64() {
        assertEquals(LogicalType.FLOAT64, JdbcTypes.logical(Types.FLOAT));
        assertEquals(LogicalType.FLOAT64, JdbcTypes.logical(Types.DOUBLE));
    }

    @Test
    public void mapsOtherFamilies() {
        assertEquals(LogicalType.INT16, JdbcTypes.logical(Types.SMALLINT));
        assertEquals(LogicalType.INT32, JdbcTypes.logical(Types.INTEGER));
        assertEquals(LogicalType.INT64, JdbcTypes.logical(Types.BIGINT));
        assertEquals(LogicalType.DECIMAL, JdbcTypes.logical(Types.NUMERIC));
        assertEquals(LogicalType.BOOLEAN, JdbcTypes.logical(Types.BIT));
        assertEquals(LogicalType.STRING, JdbcTypes.logical(Types.LONGVARCHAR));
    }
}
