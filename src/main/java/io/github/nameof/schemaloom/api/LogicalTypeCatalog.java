package io.github.nameof.schemaloom.api;

import org.apache.poi.ss.usermodel.DateUtil;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.Base64;

/**
 * 全局类型注册表，将每个 {@link LogicalType} 映射到对应的 {@link LogicalTypeDefinition}，提供统一的类型定义查询入口。
 * */
public final class LogicalTypeCatalog {
    private static final Map<LogicalType, LogicalTypeDefinition> DEFINITIONS = createDefinitions();

    private LogicalTypeCatalog() { }

    public static LogicalTypeDefinition get(LogicalType type) {
        LogicalTypeDefinition definition = DEFINITIONS.get(type);
        if (definition == null) throw new IllegalArgumentException("logical type is not registered: " + type);
        return definition;
    }

    public static Map<LogicalType, LogicalTypeDefinition> definitions() { return DEFINITIONS; }

    public static void validateValue(LogicalType type, Object value) {
        if (value == null) return;
        Class<?> expected = get(type).javaType();
        if (!expected.isInstance(value))
            throw new IllegalArgumentException("logical type " + type + " requires " + expected.getName()
                    + " but received " + value.getClass().getName());
    }

    private static Map<LogicalType, LogicalTypeDefinition> createDefinitions() {
        EnumMap<LogicalType, LogicalTypeDefinition> definitions = new EnumMap<>(LogicalType.class);
        // 标量类型共用相同的文本/Excel/JDBC 结构，只把 Java 类型和具体 JDBC 操作传入模板。
        definitions.put(LogicalType.BOOLEAN, scalar(LogicalType.BOOLEAN, Boolean.class, Boolean::valueOf, Types.BOOLEAN,
                (rs, i) -> nullable(rs, i, Boolean.class), (ps, i, v) -> ps.setBoolean(i, (Boolean) v)));
        definitions.put(LogicalType.INT16, scalar(LogicalType.INT16, Short.class, Short::valueOf, Types.SMALLINT,
                (rs, i) -> nullable(rs, i, Short.class), (ps, i, v) -> ps.setShort(i, (Short) v)));
        definitions.put(LogicalType.INT32, scalar(LogicalType.INT32, Integer.class, Integer::valueOf, Types.INTEGER,
                (rs, i) -> nullable(rs, i, Integer.class), (ps, i, v) -> ps.setInt(i, (Integer) v)));
        definitions.put(LogicalType.INT64, scalar(LogicalType.INT64, Long.class, Long::valueOf, Types.BIGINT,
                (rs, i) -> nullable(rs, i, Long.class), (ps, i, v) -> ps.setLong(i, (Long) v)));
        definitions.put(LogicalType.DECIMAL, scalar(LogicalType.DECIMAL, BigDecimal.class, BigDecimal::new, Types.DECIMAL,
                (rs, i) -> nullable(rs, i, BigDecimal.class), (ps, i, v) -> ps.setBigDecimal(i, (BigDecimal) v)));
        definitions.put(LogicalType.FLOAT32, scalar(LogicalType.FLOAT32, Float.class, Float::valueOf, Types.REAL,
                (rs, i) -> nullable(rs, i, Float.class), (ps, i, v) -> ps.setFloat(i, (Float) v)));
        definitions.put(LogicalType.FLOAT64, scalar(LogicalType.FLOAT64, Double.class, Double::valueOf, Types.DOUBLE,
                (rs, i) -> nullable(rs, i, Double.class), (ps, i, v) -> ps.setDouble(i, (Double) v)));
        definitions.put(LogicalType.STRING, scalar(LogicalType.STRING, String.class, text -> text, Types.VARCHAR,
                ResultSet::getString, (ps, i, v) -> ps.setString(i, (String) v)));

        // 特殊类型，需逐个定义编解码方式
        definitions.put(LogicalType.DATE, date());
        definitions.put(LogicalType.TIME, time());
        definitions.put(LogicalType.TIMESTAMP, timestamp());
        definitions.put(LogicalType.BINARY, binary());
        definitions.put(LogicalType.OFFSET_TIME, offsetTime());
        definitions.put(LogicalType.OFFSET_TIMESTAMP, offsetTimestamp());

        // 新增 LogicalType 后若忘记注册，立即失败，避免遗漏被 default 分支掩盖。
        if (definitions.size() != LogicalType.values().length)
            throw new IllegalStateException("LogicalType catalog is incomplete");
        return Collections.unmodifiableMap(definitions);
    }

    /**
     * 仅用于没有特殊外部表示的标量类型
     * 文本：标量类型可自由转换为String或对象
     * excel：decode用字符串中转简单处理即可；encode 直接透传 Java 标量对象
     * jdbc：标量类型直接rs.get、ps.setXxx即可，无需特殊处理
     */
    private static LogicalTypeDefinition scalar(LogicalType type, Class<?> javaType, Parser parser, int sqlType,
                                                JdbcReader reader, JdbcWriter writer) {
        return new LogicalTypeDefinition(type, javaType,
                new LogicalTypeDefinition.TextCodec() {
                    public Object parse(String text) { return parser.parse(text); }
                    public String format(Object value) { return String.valueOf(value); }
                },
                new LogicalTypeDefinition.ExcelCodec() {
                    public Object decode(Object raw) { return parseExcelScalar(javaType, raw); }
                    public Object encode(Object value) { return value; }
                }, jdbc(sqlType, reader, writer));
    }

    private static LogicalTypeDefinition date() {
        // Excel 日期可能以 Date、数字序列号或数字字符串返回，统一还原为 LocalDate。
        return new LogicalTypeDefinition(LogicalType.DATE, LocalDate.class, text(LocalDate::parse), new LogicalTypeDefinition.ExcelCodec() {
            public Object decode(Object raw) { return excelDate(raw).toLocalDate(); }
            public Object encode(Object value) { return java.sql.Date.valueOf((LocalDate) value); }
        }, jdbc(Types.DATE, (rs, i) -> { java.sql.Date value = rs.getDate(i); return value == null ? null : value.toLocalDate(); },
                (ps, i, v) -> ps.setDate(i, java.sql.Date.valueOf((LocalDate) v))));
    }

    private static LogicalTypeDefinition time() {
        return new LogicalTypeDefinition(LogicalType.TIME, LocalTime.class, text(LocalTime::parse), new LogicalTypeDefinition.ExcelCodec() {
            public Object decode(Object raw) { return LocalTime.parse(String.valueOf(raw)); }
            public Object encode(Object value) { return value.toString(); }
        }, jdbc(Types.TIME, (rs, i) -> { Time value = rs.getTime(i); return value == null ? null : value.toLocalTime(); },
                (ps, i, v) -> ps.setTime(i, Time.valueOf((LocalTime) v))));
    }

    private static LogicalTypeDefinition timestamp() {
        // TIMESTAMP 保留本地日期时间，不在 Excel/JDBC 边界隐式引入时区。
        return new LogicalTypeDefinition(LogicalType.TIMESTAMP, LocalDateTime.class, text(LocalDateTime::parse), new LogicalTypeDefinition.ExcelCodec() {
            public Object decode(Object raw) { return excelDate(raw); }
            public Object encode(Object value) { return Timestamp.valueOf((LocalDateTime) value); }
        }, jdbc(Types.TIMESTAMP, (rs, i) -> { Timestamp value = rs.getTimestamp(i); return value == null ? null : value.toLocalDateTime(); },
                (ps, i, v) -> ps.setTimestamp(i, Timestamp.valueOf((LocalDateTime) v))));
    }

    private static LogicalTypeDefinition binary() {
        // 文件文本边界统一使用 Base64；DataRecord 内部仍保持 byte[] 标准类型。
        return new LogicalTypeDefinition(LogicalType.BINARY, byte[].class, new LogicalTypeDefinition.TextCodec() {
            public Object parse(String text) { return Base64.getDecoder().decode(text); }
            public String format(Object value) { return Base64.getEncoder().encodeToString((byte[]) value); }
        }, new LogicalTypeDefinition.ExcelCodec() {
            public Object decode(Object raw) { return Base64.getDecoder().decode(String.valueOf(raw)); }
            public Object encode(Object value) { return Base64.getEncoder().encodeToString((byte[]) value); }
        }, jdbc(Types.BINARY, ResultSet::getBytes, (ps, i, v) -> ps.setBytes(i, (byte[]) v)));
    }

    private static LogicalTypeDefinition offsetTime() {
        // Excel 没有带时区的原生单元格类型，因此使用 ISO-8601 字符串。
        return new LogicalTypeDefinition(LogicalType.OFFSET_TIME, OffsetTime.class, text(OffsetTime::parse), isoExcel(OffsetTime::parse),
                jdbc(Types.TIME_WITH_TIMEZONE, (rs, i) -> rs.getObject(i, OffsetTime.class),
                        (ps, i, v) -> ps.setObject(i, v, Types.TIME_WITH_TIMEZONE)));
    }

    private static LogicalTypeDefinition offsetTimestamp() {
        return new LogicalTypeDefinition(LogicalType.OFFSET_TIMESTAMP, OffsetDateTime.class, text(OffsetDateTime::parse), isoExcel(OffsetDateTime::parse),
                jdbc(Types.TIMESTAMP_WITH_TIMEZONE, (rs, i) -> rs.getObject(i, OffsetDateTime.class),
                        (ps, i, v) -> ps.setObject(i, v, Types.TIMESTAMP_WITH_TIMEZONE)));
    }

    private static LogicalTypeDefinition.TextCodec text(Parser parser) {
        return new LogicalTypeDefinition.TextCodec() {
            public Object parse(String value) { return parser.parse(value); }
            public String format(Object value) { return String.valueOf(value); }
        };
    }

    private static LogicalTypeDefinition.ExcelCodec isoExcel(Parser parser) {
        return new LogicalTypeDefinition.ExcelCodec() {
            public Object decode(Object raw) { return parser.parse(String.valueOf(raw)); }
            public Object encode(Object value) { return String.valueOf(value); }
        };
    }

    private static LogicalTypeDefinition.JdbcCodec jdbc(int sqlType, JdbcReader reader, JdbcWriter writer) {
        return new LogicalTypeDefinition.JdbcCodec() {
            public Object read(ResultSet resultSet, int index) throws SQLException { return reader.read(resultSet, index); }
            public void write(PreparedStatement statement, int index, Object value) throws SQLException { writer.write(statement, index, value); }
            public int sqlType() { return sqlType; }
        };
    }

    private static Object parseExcelScalar(Class<?> type, Object raw) {
        String text = String.valueOf(raw);
        if (type == Boolean.class) {
            if (raw instanceof Boolean) return raw;
            if ("true".equalsIgnoreCase(text)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(text)) return Boolean.FALSE;
            throw new IllegalArgumentException("invalid boolean value: " + text);
        }
        if (type == Short.class) return new BigDecimal(text).stripTrailingZeros().shortValueExact();
        if (type == Integer.class) return new BigDecimal(text).stripTrailingZeros().intValueExact();
        if (type == Long.class) return new BigDecimal(text).stripTrailingZeros().longValueExact();
        if (type == BigDecimal.class) return raw instanceof BigDecimal ? raw : new BigDecimal(text);
        if (type == Float.class) return raw instanceof Number ? ((Number) raw).floatValue() : Float.valueOf(text);
        if (type == Double.class) return raw instanceof Number ? ((Number) raw).doubleValue() : Double.valueOf(text);
        return text;
    }

    private static LocalDateTime excelDate(Object raw) {
        // Hutool/POI 对日期数据不同读取路径可能返回 Date、Number 或数字字符串，全部按 Excel serial 处理。
        java.util.Date date;
        if (raw instanceof java.util.Date) date = (java.util.Date) raw;
        else if (raw instanceof Number) date = DateUtil.getJavaDate(((Number) raw).doubleValue());
        else {
            String text = String.valueOf(raw);
            if (text.matches("[-+]?\\d+(\\.\\d+)?")) date = DateUtil.getJavaDate(Double.parseDouble(text));
            else return LocalDateTime.parse(text);
        }
        return Instant.ofEpochMilli(date.getTime()).atZone(TimeZone.getDefault().toZoneId()).toLocalDateTime();
    }

    /**
     * JDBC中，对标量类型读取值后，再通过 rs.wasNull() 显式检测是否为null值，确保返回java null 而非厂商驱动可能返回的默认值，避免出现不一致行为和数据
     * 例如java.sql.ResultSet#getBoolean(int) 在SQL结果为NULL时，会返回false值
     * 字符串类型可不用处理，java.sql.ResultSet#getString(int) 已约定如果SQL结果为NULL，会返回java null
     */
    private static <T> T nullable(ResultSet rs, int index, Class<T> type) throws SQLException {
        T value = rs.getObject(index, type);
        return rs.wasNull() ? null : value;
    }

    private interface Parser { Object parse(String text); }
    private interface JdbcReader { Object read(ResultSet resultSet, int index) throws SQLException; }
    private interface JdbcWriter { void write(PreparedStatement statement, int index, Object value) throws SQLException; }
}
