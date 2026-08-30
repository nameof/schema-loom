package io.github.nameof.schemaloom.dialect;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.metadata.TableInfo;
import io.github.nameof.schemaloom.metadata.IndexInfo;

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

    /** 根据源表元数据生成 CREATE TABLE SQL，包括可安全迁移的默认值。 */
    String createTableSql(String table, TableInfo source);
    String createTableSql(String table, RecordSchema source);
    List<String> commentSql(String table, TableInfo source);
    List<String> indexSql(String table, List<IndexInfo> indexes);

    /**
     * 根据已引用的表名生成 DROP TABLE SQL。
     */
    String dropTableSql(String table);

    /** 根据已引用的表名生成重命名表 SQL。 */
    String renameTableSql(String table, String newTable);

    /** 根据从源数据库获取的 SELECT 定义创建视图。 */
    String createViewSql(String view, String definition);

    /** 删除已引用的视图名称。 */
    String dropViewSql(String view);

    /** 构建用于读取视图定义的数据库专用参数化查询。 */
    ViewDefinitionQuery viewDefinitionQuery(DatabaseConnectionInfo source, QualifiedTableName view);

    /**
     * 根据指定 Schema 生成参数化 INSERT SQL。
     */
    String insertSql(String table, RecordSchema schema);
}
