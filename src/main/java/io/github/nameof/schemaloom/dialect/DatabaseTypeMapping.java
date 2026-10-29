package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.FieldSchema;
import io.github.nameof.schemaloom.api.LogicalType;

import java.util.Objects;
import java.util.function.Function;

public final class DatabaseTypeMapping {
    /**
     * 指定数据库是否支持某个数据类型
     * 大多数情况下，尽量不要做unsupported声明，而是通过代码转换为兼容的类型
     * 例如某个数据库不支持带时区的时间类型，尽量不要抛出unsupported导致拒绝任务，而是通过代码将数据转为无时区的时间类型，来做到supported
     */
    private final boolean supported;
    /** 根据字段的长度、精度等属性生成最终可执行的数据库 DDL 类型。 */
    private final Function<FieldSchema, String> ddlTypeResolver;
    /**
     * 本方言把该逻辑类型降级成了哪个逻辑类型（例如带时区的时间降级为无时区的时间）。
     * 降级必须同时作用于 DDL 和 JDBC 绑定，否则会出现「DDL 是 TIME、绑定却用 TIME_WITH_TIMEZONE」的错配，
     * 目标驱动会直接报「无法识别的 JDBC 类型」（DB2 实测 ERRORCODE=-4228）。
     */
    private final LogicalType degradesTo;

    private DatabaseTypeMapping(boolean supported, Function<FieldSchema, String> ddlTypeResolver, LogicalType degradesTo) {
        this.supported = supported;
        this.ddlTypeResolver = ddlTypeResolver;
        this.degradesTo = degradesTo;
    }

    public static DatabaseTypeMapping supported(String ddlType) {
        if (ddlType == null || ddlType.trim().isEmpty()) throw new IllegalArgumentException("ddlType is blank");
        return supported(field -> ddlType);
    }

    /**
     * 声明降级：DDL 使用 ddlType，JDBC 绑定改用 degradesTo 的编解码与类型码。
     * 调用方应据此向使用者告警，而不是静默丢弃精度/时区信息。
     */
    public static DatabaseTypeMapping degraded(String ddlType, LogicalType degradesTo) {
        if (ddlType == null || ddlType.trim().isEmpty()) throw new IllegalArgumentException("ddlType is blank");
        return new DatabaseTypeMapping(true, field -> ddlType,
                Objects.requireNonNull(degradesTo, "degradesTo is required"));
    }

    public static DatabaseTypeMapping supported(Function<FieldSchema, String> ddlTypeResolver) {
        return new DatabaseTypeMapping(true, Objects.requireNonNull(ddlTypeResolver, "ddlTypeResolver"), null);
    }

    public static DatabaseTypeMapping unsupported() { return new DatabaseTypeMapping(false, null, null); }
    public boolean isSupported() { return supported; }

    /** 降级后的逻辑类型；null 表示未降级。 */
    public LogicalType degradesTo() { return degradesTo; }

    public String getDdlType(FieldSchema field) {
        if (!supported) throw new IllegalStateException("database type mapping is unsupported");
        String ddlType = ddlTypeResolver.apply(Objects.requireNonNull(field, "field"));
        if (ddlType == null || ddlType.trim().isEmpty())
            throw new IllegalStateException("resolved ddlType is blank");
        return ddlType;
    }
}
