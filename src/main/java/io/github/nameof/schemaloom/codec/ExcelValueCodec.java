package io.github.nameof.schemaloom.codec;

import io.github.nameof.schemaloom.api.*;

public final class ExcelValueCodec {
    private ExcelValueCodec() { }

    public static Object decode(FieldSchema field, Object raw) {
        try {
            return LogicalTypeCatalog.get(field.getLogicalType()).decodeExcel(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid XLSX value for field '" + field.getName() + "': " + e.getMessage(), e);
        }
    }

    public static Object encode(FieldSchema field, Object value) {
        try {
            LogicalTypeCatalog.validateValue(field.getLogicalType(), value);
            return LogicalTypeCatalog.get(field.getLogicalType()).encodeExcel(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid XLSX value for field '" + field.getName() + "': " + e.getMessage(), e);
        }
    }
}
