package io.github.nameof.schemaloom.api;

import java.util.*;

public final class RecordSchema {
    private final List<FieldSchema> fields;
    public RecordSchema(List<FieldSchema> fields) {
        if (fields == null || fields.isEmpty()) throw new IllegalArgumentException("schema requires fields");
        List<FieldSchema> copy = new ArrayList<FieldSchema>(fields);

        Set<String> names = new HashSet<String>();
        for (FieldSchema f : copy)
            if (!names.add(f.getName()))
                throw new IllegalArgumentException("duplicate field: " + f.getName());
        this.fields = Collections.unmodifiableList(copy);
    }

    public List<FieldSchema> getFields() {
        return fields;
    }

    public int indexOf(String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).getName().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown field: " + name);
    }

    public FieldSchema field(String name) {
        return fields.get(indexOf(name));
    }
}
