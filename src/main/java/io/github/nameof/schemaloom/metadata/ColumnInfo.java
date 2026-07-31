package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.api.LogicalType;

public final class ColumnInfo {
    private final String name, typeName, remarks;
    private final LogicalType logicalType;
    private final int ordinal;
    private final boolean nullable;
    private final Integer length, precision, scale;
    public ColumnInfo(String name, String typeName, String remarks, LogicalType logicalType, int ordinal,
                      boolean nullable, Integer length, Integer precision, Integer scale) {
        this.name = name; this.typeName = typeName; this.remarks = remarks; this.logicalType = logicalType;
        this.ordinal = ordinal; this.nullable = nullable; this.length = length; this.precision = precision; this.scale = scale;
    }
    public String getName() { return name; } public String getTypeName() { return typeName; } public String getRemarks() { return remarks; }
    public LogicalType getLogicalType() { return logicalType; } public int getOrdinal() { return ordinal; } public boolean isNullable() { return nullable; }
    public Integer getLength() { return length; } public Integer getPrecision() { return precision; } public Integer getScale() { return scale; }
}
