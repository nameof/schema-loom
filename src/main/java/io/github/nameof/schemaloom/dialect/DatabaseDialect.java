package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;

import java.util.*;

public interface DatabaseDialect {
    /**
     * 引用单个数据库标识符，并转义标识符内部的引用字符。
     */
    String quote(String identifier);

    /**
     * 引用限定表名；存在 catalog 和 schema 时一并处理。
     */
    String quote(QualifiedTableName table);

    /**
     * 返回 SchemaLoom 字段对应的数据库 DDL 类型。
     */
    String type(FieldSchema field);

    /**
     * 返回一个逻辑类型的显式数据库能力映射。
     */
    DatabaseTypeMapping mapping(LogicalType type);

    /**
     * 根据指定 Schema 生成 CREATE TABLE SQL。
     */
    String createTable(String table, RecordSchema schema);

    /**
     * 根据已引用的表名生成 DROP TABLE SQL。
     */
    String dropTable(String table);

    /**
     * 根据指定 Schema 生成参数化 INSERT SQL。
     */
    String insert(String table, RecordSchema schema);
}
