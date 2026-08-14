package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.*;

public final class ExcelValueCodec {
    private ExcelValueCodec() { }

    public static Object decode(FieldSchema field, Object raw) {
        try {
            return LogicalTypeCatalog.get(field.getLogicalType()).decodeExcel(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid XLSX value for field '" + field.getName() + "'", e);
        }
    }

    public static Object encode(FieldSchema field, Object value) {
        LogicalTypeCatalog.validateValue(field.getLogicalType(), value);
        return LogicalTypeCatalog.get(field.getLogicalType()).encodeExcel(value);
    }
}
