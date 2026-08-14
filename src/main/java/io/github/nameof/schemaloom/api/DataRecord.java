package io.github.nameof.schemaloom.api;

import java.util.*;

public final class DataRecord {
    private final RecordSchema schema;
    private final List<Object> values;

    public DataRecord(RecordSchema schema, List<?> values) {
        this.schema = Objects.requireNonNull(schema, "schema");
        if (values == null || values.size() != schema.getFields().size())
            throw new IllegalArgumentException("value count differs from schema");
        List<Object> copy = new ArrayList<Object>(values);
        for (int i = 0; i < copy.size(); i++)
            LogicalTypeCatalog.validateValue(schema.getFields().get(i).getLogicalType(), copy.get(i));
        this.values = Collections.unmodifiableList(copy);
    }

    public RecordSchema getSchema() {
        return schema;
    }

    public Object get(int index) {
        return values.get(index);
    }

    public Object get(String name) {
        return get(schema.indexOf(name));
    }

    public List<Object> getValues() {
        return values;
    }

    public DataRecord with(int index, Object value) {
        List<Object> copy = new ArrayList<Object>(values);
        copy.set(index, value);
        return new DataRecord(schema, copy);
    }

    public DataRecord with(String name, Object value) {
        return with(schema.indexOf(name), value);
    }
}
