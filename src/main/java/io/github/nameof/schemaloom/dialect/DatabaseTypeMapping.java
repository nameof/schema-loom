package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.FieldSchema;

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

    private DatabaseTypeMapping(boolean supported, Function<FieldSchema, String> ddlTypeResolver) {
        this.supported = supported;
        this.ddlTypeResolver = ddlTypeResolver;
    }

    public static DatabaseTypeMapping supported(String ddlType) {
        if (ddlType == null || ddlType.trim().isEmpty()) throw new IllegalArgumentException("ddlType is blank");
        return supported(field -> ddlType);
    }

    public static DatabaseTypeMapping supported(Function<FieldSchema, String> ddlTypeResolver) {
        return new DatabaseTypeMapping(true, Objects.requireNonNull(ddlTypeResolver, "ddlTypeResolver"));
    }

    public static DatabaseTypeMapping unsupported() { return new DatabaseTypeMapping(false, null); }
    public boolean isSupported() { return supported; }

    public String getDdlType(FieldSchema field) {
        if (!supported) throw new IllegalStateException("database type mapping is unsupported");
        String ddlType = ddlTypeResolver.apply(Objects.requireNonNull(field, "field"));
        if (ddlType == null || ddlType.trim().isEmpty())
            throw new IllegalStateException("resolved ddlType is blank");
        return ddlType;
    }
}
