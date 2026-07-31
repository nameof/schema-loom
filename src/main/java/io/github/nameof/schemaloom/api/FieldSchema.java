package io.github.nameof.schemaloom.api;

import java.util.Objects;

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
