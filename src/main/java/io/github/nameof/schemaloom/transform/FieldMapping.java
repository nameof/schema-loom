package io.github.nameof.schemaloom.transform;

import io.github.nameof.schemaloom.api.*;

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
        for (String k : source.getPrimaryKeyFields())
            for (FieldMapping m : ms) if (m.source.equals(k)) keys.add(m.target);
        return new RecordSchema(fs, keys);
    }

    public static DataRecord mapRecord(DataRecord record, RecordSchema target, List<FieldMapping> mappings) {
        List<FieldMapping> ms = mappings == null || mappings.isEmpty() ? identity(record.getSchema()) : mappings;
        List<Object> values = new ArrayList<Object>();
        for (FieldMapping m : ms) values.add(record.get(m.source));
        return new DataRecord(target, values);
    }

    private static List<FieldMapping> identity(RecordSchema s) {
        List<FieldMapping> out = new ArrayList<FieldMapping>();
        for (FieldSchema f : s.getFields()) out.add(new FieldMapping(f.getName(), f.getName()));
        return out;
    }
}
