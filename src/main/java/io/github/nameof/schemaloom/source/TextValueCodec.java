package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.LogicalType;
import io.github.nameof.schemaloom.api.LogicalTypeCatalog;

public final class TextValueCodec {
    private TextValueCodec() { }

    public static Object parse(LogicalType type, String text) {
        try {
            return LogicalTypeCatalog.get(type).parseText(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid text for logical type " + type, e);
        }
    }

    public static String format(LogicalType type, Object value) {
        return LogicalTypeCatalog.get(type).formatText(value);
    }
}
