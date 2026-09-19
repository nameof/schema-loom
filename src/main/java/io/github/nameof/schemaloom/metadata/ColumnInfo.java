package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.api.LogicalType;
import io.github.nameof.schemaloom.api.FieldSchema;
import lombok.Getter;

/**
 * 数据库表列的完整元数据。
 *
 * <p>与 {@link FieldSchema} 的共同部分由 {@link #fieldSchema} 提供，包括名称、逻辑类型、
 * 可空性、长度、精度和小数位；本类额外保存数据库专属信息，如原始类型名、列注释、默认值、
 * 生成表达式、序号、自增和生成列标记。</p>
 *
 * <p>{@code FieldSchema} 面向所有数据源的数据读写，{@code ColumnInfo} 仅面向数据库结构读取
 * 和迁移。JDBC 类型号用于保留源列的 JDBC 语义，不能由逻辑类型反推。二者不是两套独立的字段定义：数据库列的通用字段定义应始终通过
 * {@link #getFieldSchema()} 获取。</p>
 */
@Getter
public final class ColumnInfo {
    private final FieldSchema fieldSchema;
    private final String typeName, remarks, defaultValue, generatedExpression;
    private final int jdbcType;
    private final int ordinal;
    private final boolean autoIncremented, generated;
    public ColumnInfo(String name, String typeName, String remarks, LogicalType logicalType, int jdbcType, int ordinal,
                      boolean nullable, Integer length, Integer precision, Integer scale) {
        this(new FieldSchema(name, logicalType, nullable, length, precision, scale), typeName, remarks, ordinal,
                jdbcType, null, null, false, false);
    }
    public ColumnInfo(String name, String typeName, String remarks, LogicalType logicalType, int jdbcType, int ordinal,
                      boolean nullable, Integer length, Integer precision, Integer scale, String defaultValue,
                      String generatedExpression, boolean autoIncremented, boolean generated) {
        this(new FieldSchema(name, logicalType, nullable, length, precision, scale), typeName, remarks, ordinal,
                jdbcType, defaultValue, generatedExpression, autoIncremented, generated);
    }

    public ColumnInfo(FieldSchema fieldSchema, String typeName, String remarks, int ordinal, int jdbcType, String defaultValue,
                      String generatedExpression, boolean autoIncremented, boolean generated) {
        if (fieldSchema == null) throw new IllegalArgumentException("字段定义不能为空");
        if (jdbcType == 0) throw new IllegalArgumentException("JDBC 类型不能为空或 Types.NULL");
        this.fieldSchema = fieldSchema;
        this.typeName = typeName;
        this.remarks = remarks;
        this.ordinal = ordinal;
        this.jdbcType = jdbcType;
        this.defaultValue = defaultValue;
        this.generatedExpression = generatedExpression;
        this.autoIncremented = autoIncremented;
        this.generated = generated;
    }

    public FieldSchema getFieldSchema() { return fieldSchema; }
    public String getName() { return fieldSchema.getName(); }
    public LogicalType getLogicalType() { return fieldSchema.getLogicalType(); }
    public boolean isNullable() { return fieldSchema.isNullable(); }
    public Integer getLength() { return fieldSchema.getLength(); }
    public Integer getPrecision() { return fieldSchema.getPrecision(); }
    public Integer getScale() { return fieldSchema.getScale(); }
    public String getTypeName() { return typeName; }
    public int getJdbcType() { return jdbcType; }
    public String getRemarks() { return remarks; }
    public int getOrdinal() { return ordinal; }
    public String getDefaultValue() { return defaultValue; }
    public String getGeneratedExpression() { return generatedExpression; }
    public boolean isAutoIncremented() { return autoIncremented; }
    public boolean isGenerated() { return generated; }
}
