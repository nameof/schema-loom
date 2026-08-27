package io.github.nameof.schemaloom.api;

import java.util.Objects;

/**
 * 跨数据源通用的字段定义。
 *
 * <p>它只描述数据读写所需的逻辑属性：字段名、逻辑类型、可空性以及长度、精度和小数位，
 * 可用于数据库、CSV、Excel、查询结果和内存记录等场景。它不包含数据库原始类型、默认值、
 * 注释、自增或生成列表达式等数据库专属信息。</p>
 *
 * <p>{@link io.github.nameof.schemaloom.metadata.ColumnInfo} 是数据库列的增强定义，内部复用
 * 一个 {@code FieldSchema}，并在此基础上增加数据库元数据。需要通用字段语义时使用本类，
 * 需要数据库结构迁移信息时使用 {@code ColumnInfo}。</p>
 */
public final class FieldSchema {
    private final String name;
    private final LogicalType logicalType;
    private final boolean nullable;
    private final Integer length;
    private final Integer precision;
    private final Integer scale;

    public FieldSchema(String name, LogicalType logicalType, boolean nullable,
                       Integer length, Integer precision, Integer scale) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("field name is blank");
        this.name = name;
        this.logicalType = Objects.requireNonNull(logicalType, "logicalType");
        if (length != null && length < 0) throw new IllegalArgumentException("length must be non-negative");
        if (precision != null && precision < 0) throw new IllegalArgumentException("precision must be non-negative");
        if (scale != null && scale < 0) throw new IllegalArgumentException("scale must be non-negative");
        this.nullable = nullable;
        this.length = length;
        this.precision = precision;
        this.scale = scale;
    }

    public static FieldSchema of(String name, LogicalType type) {
        return new FieldSchema(name, type, true, null, null, null);
    }

    public String getName() {
        return name;
    }

    public LogicalType getLogicalType() {
        return logicalType;
    }

    public boolean isNullable() {
        return nullable;
    }

    public Integer getLength() {
        return length;
    }

    public Integer getPrecision() {
        return precision;
    }

    public Integer getScale() {
        return scale;
    }
}
