package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
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
     * 返回一个逻辑类型的显式数据库能力和 DDL 类型映射。
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

    /** Creates a view from a SELECT definition obtained from the source database. */
    String createView(String view, String definition);

    /** Drops an already quoted view name. */
    String dropView(String view);

    /** Builds the database-specific, parameterized query used to read a view definition. */
    ViewDefinitionQuery viewDefinitionQuery(DatabaseConnectionInfo source, QualifiedTableName view);

    /**
     * 根据指定 Schema 生成参数化 INSERT SQL。
     */
    String insert(String table, RecordSchema schema);
}
