package io.github.nameof.schemaloom.api;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 定义单个LogicalType对应的 Java 数据类型及其在 Text / Excel / JDBC 三种边界上的编解码规则
 */
public final class LogicalTypeDefinition {
    /** 文本边界：负责 CSV 等纯文本格式与标准 Java 值之间的转换。 */
    public interface TextCodec {
        Object parse(String text);
        String format(Object value);
    }

    /** Excel 边界：负责单元格原始值与标准 Java 值之间的转换。 */
    public interface ExcelCodec {
        Object decode(Object raw);
        Object encode(Object value);
    }

    /** JDBC 边界：同时定义读、写和 null 值绑定所需的 SQL 类型。 */
    public interface JdbcCodec {
        Object read(ResultSet resultSet, int index) throws SQLException;
        void write(PreparedStatement statement, int index, Object value) throws SQLException;
        int sqlType();
    }

    private final LogicalType type;
    private final Class<?> javaType;
    private final TextCodec text;
    private final ExcelCodec excel;
    private final JdbcCodec jdbc;

    public LogicalTypeDefinition(LogicalType type, Class<?> javaType, TextCodec text, ExcelCodec excel, JdbcCodec jdbc) {
        // 每种 LogicalType 必须显式声明所有边界能力；不允许依赖默认转换或静默降级。
        if (type == null || javaType == null || text == null || excel == null || jdbc == null)
            throw new IllegalArgumentException("logical type definition is incomplete");
        this.type = type;
        this.javaType = javaType;
        this.text = text;
        this.excel = excel;
        this.jdbc = jdbc;
    }

    public LogicalType type() { return type; }
    public Class<?> javaType() { return javaType; }
    public Object parseText(String value) {     return value == null || value.isEmpty() ? null : text.parse(value); }
    public String formatText(Object value) {
        LogicalTypeCatalog.validateValue(type, value);
        return value == null ? "" : text.format(value);
    }
    public Object decodeExcel(Object raw) { return raw == null ? null : excel.decode(raw); }
    public Object encodeExcel(Object value) {
        LogicalTypeCatalog.validateValue(type, value);
        return value == null ? null : excel.encode(value);
    }
    public Object readJdbc(ResultSet resultSet, int index) throws SQLException { return jdbc.read(resultSet, index); }
    public void writeJdbc(PreparedStatement statement, int index, Object value) throws SQLException {
        // null 不能交给驱动自行猜测类型，否则不同数据库的行为会不一致。
        LogicalTypeCatalog.validateValue(type, value);
        if (value == null) statement.setNull(index, jdbc.sqlType()); else jdbc.write(statement, index, value);
    }
    public int jdbcSqlType() { return jdbc.sqlType(); }
}
