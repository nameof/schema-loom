package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.api.LogicalType;
import lombok.Getter;

@Getter
public final class ColumnInfo {
    private final String name, typeName, remarks, defaultValue, generatedExpression;
    private final LogicalType logicalType;
    private final int ordinal;
    private final boolean nullable;
    private final Integer length, precision, scale;
    private final boolean autoIncremented, generated;
    public ColumnInfo(String name, String typeName, String remarks, LogicalType logicalType, int ordinal,
                      boolean nullable, Integer length, Integer precision, Integer scale) {
        this.name = name; this.typeName = typeName; this.remarks = remarks; this.logicalType = logicalType;
        this.ordinal = ordinal; this.nullable = nullable; this.length = length; this.precision = precision; this.scale = scale;
        this.defaultValue = null; this.generatedExpression = null; this.autoIncremented = false; this.generated = false;
    }
    public ColumnInfo(String name, String typeName, String remarks, LogicalType logicalType, int ordinal,
                      boolean nullable, Integer length, Integer precision, Integer scale, String defaultValue,
                      String generatedExpression, boolean autoIncremented, boolean generated) {
        this.name = name; this.typeName = typeName; this.remarks = remarks; this.logicalType = logicalType;
        this.ordinal = ordinal; this.nullable = nullable; this.length = length; this.precision = precision; this.scale = scale;
        this.defaultValue = defaultValue; this.generatedExpression = generatedExpression;
        this.autoIncremented = autoIncremented; this.generated = generated;
    }
}
