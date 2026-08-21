package io.github.nameof.schemaloom.transform;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.metadata.*;

import java.util.*;

public final class FieldMapping {
    private final String source;
    private final String target;

    public FieldMapping(String source, String target) {
        if (source == null || target == null || source.trim().isEmpty() || target.trim().isEmpty())
            throw new IllegalArgumentException("field mapping name is blank");
        this.source = source;
        this.target = target;
    }

    public String getSource() {
        return source;
    }

    public String getTarget() {
        return target;
    }

    /** 按映射将源字段schema转换成目标字段schema；未提供映射时保留字段，并同步转换主键字段名。 */
    public static RecordSchema mapSchema(RecordSchema source, List<FieldMapping> mappings) {
        List<FieldMapping> ms = mappings == null || mappings.isEmpty() ? identity(source) : mappings;
        List<FieldSchema> fs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (FieldMapping m : ms) {
            FieldSchema f = source.field(m.source);
            if (!seen.add(m.target)) throw new IllegalArgumentException("duplicate target field: " + m.target);
            fs.add(new FieldSchema(m.target, f.getLogicalType(), f.isNullable(), f.getLength(), f.getPrecision(), f.getScale()));
        }
        List<String> keys = new ArrayList<>();
        // 只有被映射到目标的源主键才继续作为目标主键，保持键约束与字段集合一致。
        for (String k : source.getPrimaryKeyFields())
            for (FieldMapping m : ms) if (m.source.equals(k)) keys.add(m.target);
        return new RecordSchema(fs, keys);
    }

    /** 映射源表元数据及其 Schema */
    public static TableInfo mapTableInfo(TableInfo source, RecordSchema target, List<FieldMapping> mappings) {
        List<FieldMapping> ms = mappings == null || mappings.isEmpty() ? identity(source.getSchema()) : mappings;
        Map<String, ColumnInfo> columns = new HashMap<String, ColumnInfo>();
        for (ColumnInfo column : source.getColumns())
            columns.put(column.getName().toLowerCase(Locale.ENGLISH), column);
        List<ColumnInfo> mapped = new ArrayList<ColumnInfo>();
        for (FieldMapping mapping : ms) {
            ColumnInfo column = columns.get(mapping.source.toLowerCase(Locale.ENGLISH));
            if (column == null) continue;
            mapped.add(new ColumnInfo(mapping.target, column.getTypeName(), column.getRemarks(), column.getLogicalType(),
                    column.getOrdinal(), column.isNullable(), column.getLength(), column.getPrecision(), column.getScale(),
                    column.getDefaultValue(), column.isAutoIncremented(), column.isGenerated()));
        }
        return new TableInfo(source.getName(), source.isView(), source.getType(), target, mapped, null,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), source.getRemarks());
    }

    /** 按映射顺序提取记录值，构造目标 Schema 对应的新记录。 */
    public static DataRecord mapRecord(DataRecord record, RecordSchema target, List<FieldMapping> mappings) {
        List<FieldMapping> ms = mappings == null || mappings.isEmpty() ? identity(record.getSchema()) : mappings;
        List<Object> values = new ArrayList<Object>();
        for (FieldMapping m : ms)
            values.add(record.get(m.source));
        return new DataRecord(target, values);
    }

    private static List<FieldMapping> identity(RecordSchema s) {
        List<FieldMapping> out = new ArrayList<FieldMapping>();
        for (FieldSchema f : s.getFields())
            out.add(new FieldMapping(f.getName(), f.getName()));
        return out;
    }
}
