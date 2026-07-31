package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;

import java.util.*;

public interface DatabaseDialect {
    String quote(String identifier);

    String type(FieldSchema field);

    String createTable(String table, RecordSchema schema);

    String dropTable(String table);

    String insert(String table, RecordSchema schema);
}
