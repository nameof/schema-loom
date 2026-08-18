package io.github.nameof.schemaloom.codec;

import io.github.nameof.schemaloom.api.*;

public final class TextValueCodec {
    private TextValueCodec() { }

    public static Object parse(LogicalType type, String text) {
        try {
            return LogicalTypeCatalog.get(type).parseText(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid text for logical type " + type + ": " + e.getMessage(), e);
        }
    }

    public static Object parse(FieldSchema field, String text) {
        try {
            return LogicalTypeCatalog.get(field.getLogicalType()).parseText(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid text for field '" + field.getName() + "': " + e.getMessage(), e);
        }
    }

    public static String format(LogicalType type, Object value) {
        try {
            return LogicalTypeCatalog.get(type).formatText(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid text value for logical type " + type + ": " + e.getMessage(), e);
        }
    }

    public static String format(FieldSchema field, Object value) {
        try {
            return LogicalTypeCatalog.get(field.getLogicalType()).formatText(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid text value for field '" + field.getName() + "': " + e.getMessage(), e);
        }
    }
}
