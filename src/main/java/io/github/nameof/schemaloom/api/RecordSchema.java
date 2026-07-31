package io.github.nameof.schemaloom.api;

import java.util.*;

public final class RecordSchema {
    private final List<FieldSchema> fields;
    private final List<String> primaryKeyFields;

    public RecordSchema(List<FieldSchema> fields) {
        this(fields, Collections.<String>emptyList());
    }

    public RecordSchema(List<FieldSchema> fields, List<String> primaryKeyFields) {
        if (fields == null || fields.isEmpty()) throw new IllegalArgumentException("schema requires fields");
        List<FieldSchema> copy = new ArrayList<FieldSchema>(fields);
        Set<String> names = new HashSet<String>();
        for (FieldSchema f : copy)
            if (!names.add(f.getName())) throw new IllegalArgumentException("duplicate field: " + f.getName());
        List<String> keys = new ArrayList<String>(primaryKeyFields == null ? Collections.<String>emptyList() : primaryKeyFields);
        for (String key : keys)
            if (!names.contains(key)) throw new IllegalArgumentException("unknown primary key: " + key);
        this.fields = Collections.unmodifiableList(copy);
        this.primaryKeyFields = Collections.unmodifiableList(keys);
    }

    public List<FieldSchema> getFields() {
        return fields;
    }

    public List<String> getPrimaryKeyFields() {
        return primaryKeyFields;
    }

    public int indexOf(String name) {
        for (int i = 0; i < fields.size(); i++) if (fields.get(i).getName().equals(name)) return i;
        return -1;
    }

    public FieldSchema field(String name) {
        int i = indexOf(name);
        if (i < 0) throw new IllegalArgumentException("unknown field: " + name);
        return fields.get(i);
    }
}
