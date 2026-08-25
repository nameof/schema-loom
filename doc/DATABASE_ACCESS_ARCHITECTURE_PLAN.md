# 数据库访问架构计划

## 目标

SchemaLoom 将数据库能力划分为三个内部层：

```text
SchemaCrawler -> DatabaseMetadataService -> SchemaLoom 元数据 DTO
Spring JDBC   -> 内部执行层 -> Source 和 Target
DatabaseDialect -> 数据库差异规则和 SQL 生成
```

- `SchemaCrawler` 是数据库结构元数据的唯一读取来源。
- `DatabaseMetadataService` 保持稳定的公共元数据门面，负责将 SchemaCrawler
  对象映射为 SchemaLoom DTO，绝不暴露 SchemaCrawler 类型。
- Spring JDBC 封装 SQL 执行、参数绑定、流式结果集、批量写入、事务和 JDBC
  异常转换。
- `DatabaseDialect` 集中数据库差异决策，只作为策略和规则层，不负责流程控制。

JDBC 仍作为连接、驱动加载、语句执行和动态查询结果元数据的传输层。SchemaCrawler
只读取数据库结构元数据，不读取业务源数据。

## 分层职责

| 层 | 职责 | 不应包含 |
| --- | --- | --- |
| `metadata` | SchemaCrawler catalog 读取、元数据过滤、DTO 映射、元数据异常转换 | 公共 API 中的 SchemaCrawler 类型、DDL、数据写入流程 |
| `DatabaseMetadataService` | 数据库、catalog、schema、表、视图、列、键、索引、约束和序列元数据的稳定门面 | 内部读取器以外分散的 `DatabaseMetaData` 调用 |
| Spring JDBC 执行层 | 查询执行、参数绑定、资源处理、流式 `ResultSet` 回调、批量更新、事务模板、异常转换 | 数据库特定的 DDL 和类型规则 |
| `DatabaseDialect` | 标识符引用、逻辑到物理类型映射、DDL、默认值、自增、注释、索引、外键、视图规则和厂商类型/绑定策略 | 连接生命周期、结果集循环、事务边界、批处理循环、通用资源清理 |
| `Source` 和 `Target` | ETL 编排和调用元数据/执行层 | 厂商 SQL 分支和直接元数据抓取 |

执行层可以向 `DatabaseDialect` 查询厂商特有的读取或绑定策略；实际 JDBC/Spring JDBC
流程由执行层负责。

## 元数据范围

`DatabaseMetadataService` 保留现有公共方法，并按需要扩展 SchemaLoom 自有 DTO。
任何公共方法都不得返回 SchemaCrawler 类型。

计划支持的元数据：

- 数据库和 JDBC 驱动信息
- Catalog 和 schema
- 表和视图
- 列：原生类型、逻辑类型、顺序、nullable、长度、精度、scale、注释、默认值、自增和生成列标记
- 主键、索引、外键和表约束
- 序列
- 表和列注释

暂不支持：

- 同义词
- 存储过程和函数
- 触发器
- 数据库用户、权限和授权

`JdbcTableTarget` 必须通过 `DatabaseMetadataService` 判断目标表是否存在和 APPEND
结构是否兼容，不得重复调用 `DatabaseMetaData.getTables()` 或 `getColumns()`。

`JdbcQuerySource` 可以继续使用 `ResultSetMetaData`，因为任意 SELECT 的输出结构必须先执行
查询，SchemaCrawler 无法替代。

## 数据库差异和迁移规则

所有数据库特定的迁移行为都归入 `DatabaseDialect`：

- 建表、删表、修改表和视图 DDL
- 逻辑类型和源端原生类型对应的物理列类型
- 标识符引用和限定表名渲染
- 默认值表达式渲染
- Identity 和自增语法
- 表和列注释
- 索引和外键 DDL，包括引用操作
- `TEXT`、`LONGTEXT`、`BLOB`、`CLOB` 等厂商类型处理
- 视图创建规则，以及视图不得参与目标普通表建表的规则

外键依赖排序和循环依赖处理属于迁移规划职责。规划器使用元数据提供的外键图，
方言负责生成数据库特定的 DDL。

## 数据执行规则

围绕现有连接生命周期引入内部 Spring JDBC 适配层，不得通过 SchemaLoom 公共 API
暴露 Spring 类型。

- 使用 `JdbcTemplate` 执行语句、绑定参数、释放资源并转换异常。
- 使用回调式处理流式读取，禁止在产出 ETL batch 前将完整结果集物化为 `List`。
- 保留 query/table source 的显式 fetch size 配置。
- 每个目标 batch 通过 `TransactionTemplate` 使用一个短事务。
- 使用 Spring JDBC 批处理能力写入，同时保留 SchemaLoom 错误策略和 batch 统计。
- 查询 SQL 必须参数化；生成的标识符和 DDL 只能来自已验证的元数据和 `DatabaseDialect`。

大字段需要显式值策略：

- `TEXT` 和 `LONGTEXT` 可作为字符串复制；超出配置阈值时拒绝或流式处理。
- `BLOB` 和 `CLOB` 不得默认无界地加载到内存；执行层支持可配置的跳过、受限物化或流式复制。
- `DatabaseDialect` 选择厂商特有 JDBC 绑定/读取策略，执行层负责实际读写。

## 实施顺序

1. 使用 SchemaCrawler 读取器和 DTO 映射器替换 `DatabaseMetadataService` 内部实现，保持现有公共 API。
2. 扩展元数据 DTO 和测试，覆盖视图、注释、默认值、生成列、自增、索引、外键、约束和序列。
3. 将 `JdbcTableSource` 和 `JdbcTableTarget` 的元数据决策统一接入 `DatabaseMetadataService`。
4. 新增内部 Spring JDBC 执行适配层，迁移查询流式读取、批量写入和事务处理，不改变公共 ETL API。
5. 扩展 `DatabaseDialect`，实现视图、索引、外键、默认值、自增和注释 DDL 规则。
6. 增加 BLOB/TEXT 值策略及跨数据库集成验证。

## 验收标准

- 公共 API 和 DTO 不暴露 SchemaCrawler 或 Spring JDBC 类型。
- 驱动生命周期以外的数据库结构读取统一通过元数据服务，不再散落直接 `DatabaseMetaData` 调用。
- 数据库特定 DDL 和值策略只由 `DatabaseDialect` 实现定义。
- 结果集遍历、连接清理、事务和批处理集中在内部执行层。
- MySQL、Oracle、SQL Server 覆盖视图、外键、索引、默认值、自增、注释及所选 BLOB/TEXT 策略测试。
- 源码和计划文档中不得记录连接串、账号、密码或其他敏感信息。
