package io.github.nameof.schemaloom.api;

import java.util.*;

public final class RecordSchema {
    private final List<FieldSchema> fields;
    private final List<String> primaryKeyFields;

    public RecordSchema(List<FieldSchema> fields) {
        this(fields, Collections.emptyList());
    }

    /** 校验字段和主键引用，并复制为不可变列表，避免调用方后续修改 Schema。 */
    public RecordSchema(List<FieldSchema> fields, List<String> primaryKeyFields) {
        if (fields == null || fields.isEmpty()) throw new IllegalArgumentException("schema requires fields");
        List<FieldSchema> copy = new ArrayList<FieldSchema>(fields);
        Set<String> names = new HashSet<String>();
        // 先建立字段名集合，后续既用于重复校验，也用于验证主键是否存在。
        for (FieldSchema f : copy)
            if (!names.add(f.getName())) throw new IllegalArgumentException("duplicate field: " + f.getName());
        List<String> keys = new ArrayList<String>(primaryKeyFields == null ? Collections.<String>emptyList() : primaryKeyFields);
        // 主键只允许引用当前 Schema 中的字段，避免产生无法读取的键定义。
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
