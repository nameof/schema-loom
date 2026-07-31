# SchemaLoom v0.1 实施计划

## 概要

- 独立 Maven 单模块项目：`io.github.nameof:schemaloom:0.1.0-SNAPSHOT`，Apache-2.0，Java 8。
- 与 `ads-backend`、Neo4j、Spring Boot、Web 框架无关。
- 支持统一数据库元数据，以及库到库、库到文件、文件到库、文件到文件的批量 ETL。
- 技术栈固定为 SchemaCrawler `16.29.1`、Spring JDBC `5.3.39`、Hutool `5.8.44`、POI `5.5.1`。
- 单模块通过 Java package 分层，不使用 Java SPI、`ServiceLoader`、插件上下文或多层 Factory。
- 提供 `MIGRATING_TO_JAVA_17.md`，说明升级 SchemaCrawler 17 和 Spring JDBC 6 的步骤。

## 简化架构

仅保留三个必要的普通接口：

```java
interface Source extends AutoCloseable {
    RecordSchema schema();
    void read(BatchConsumer consumer);
}

interface Target extends AutoCloseable {
    void prepare(RecordSchema schema, TargetMode mode);
    BatchWriteResult write(RecordBatch batch);
}

interface Transformer {
    TransformResult transform(DataRecord record);
}
```

直接提供具体实现：

```text
JdbcTableSource    JdbcQuerySource
CsvSource          XlsxSource

JdbcTableTarget    CsvTarget
XlsxTarget
```

任务 API 保持直观：

```java
EtlTask task = EtlTask.builder()
    .source(new JdbcTableSource(sourceConfig))
    .target(new CsvTarget(targetConfig))
    .transformer(Transformer.identity())
    .batchSize(1000)
    .errorPolicy(ErrorPolicy.ISOLATE_AND_CONTINUE)
    .build();

EtlResult result = task.run();
```

- `EtlTask` 实现 `Callable<EtlResult>`，自身不创建线程。
- 数据流固定为：读取 → 类型规范化 → Transformer → 字段映射 → 写入。
- 默认 Transformer 原样返回；`TransformResult.drop()` 用于过滤记录。
- v1 Transformer 只允许过滤和改写值，不动态改变字段集合。
- 新文件类型可通过实现普通 `Source` 或 `Target` 接口扩展，不需要注册 SPI。

## 包结构与数据模型

- `api`：`RecordSchema`、`FieldSchema`、`LogicalType`、`DataRecord`、`RecordBatch`、结果和异常。
- `metadata`：中立元数据接口、DTO、SchemaCrawler 映射。
- `driver`：外部 JDBC 驱动加载和连接创建。
- `source`、`target`：接口及 JDBC/CSV/XLSX 实现。
- `transform`：Transformer、字段映射和默认实现。
- `dialect`：包内数据库方言实现。
- `engine`：任务编排、批处理、错误策略。
- `runtime`：可选有界本地线程池。

`LogicalType` 固定为：

```text
BOOLEAN、INT16、INT32、INT64、DECIMAL、
FLOAT32、FLOAT64、STRING、
DATE、TIME、TIMESTAMP、BINARY
```

- DECIMAL 使用 `BigDecimal`，禁止有损转换为 Double。
- 时间使用 Java 8 `java.time` 类型，二进制使用 `byte[]`。
- UUID、JSON、XML 默认映射为 STRING。
- 未知类型默认失败，可通过 `UnsupportedTypePolicy.AS_STRING` 放宽。
- 数据记录内部保持字段顺序，不暴露 SchemaCrawler、JdbcTemplate、Hutool 类型。

## 元数据 API

`DatabaseMetadataService` 提供：

```java
DatabaseInfo getDatabaseInfo(ConnectionProvider connection);
List<CatalogInfo> listCatalogs(ConnectionProvider connection);
List<SchemaInfo> listSchemas(ConnectionProvider connection);
List<TableInfo> listTables(ConnectionProvider connection, MetadataQuery query);
TableInfo getTable(ConnectionProvider connection, QualifiedTableName table);
```

- 覆盖数据库和驱动信息、catalog、schema、表、视图、列、主键、外键、索引和注释。
- v1 不提供 procedure、function、sequence、trigger。
- 所有 SchemaCrawler 调用集中在 `metadata` 内部并映射为不可变 DTO。
- MySQL、Oracle、SQL Server 的 catalog/schema 差异由内部实现处理。
- `JdbcTableSource` 使用元数据验证表名和字段后生成 SQL。
- `JdbcQuerySource` 接受参数化 SQL 和参数列表；不提供字符串参数拼接。

## JDBC 驱动管理

- 默认从 `${user.dir}/drivers` 读取驱动，可在 Builder 中修改目录。
- 核心发行包不内置 MySQL、Oracle、SQL Server 驱动，也不通过运行时 Maven 依赖绑定驱动版本。
- 每个驱动使用一个简单 `.properties` 描述文件：

```text
id
databaseType
driverClass
classpath
urlPrefixes
priority
serverVersionRange
driverPackages
defaultProperties
```

- `classpath` 可以包含驱动及其依赖的多个 JAR。
- 显式指定 `driverId` 时直接使用；自动模式按数据库类型和 priority 依次试连。
- 连接成功后读取 `DatabaseMetaData` 的服务器版本，并校验 `serverVersionRange`。
- 使用可关闭、引用计数的 child-first `URLClassLoader` 隔离 MySQL 5、MySQL 8 等同名驱动类。
- JDK 与 SchemaLoom 类 parent-first，描述文件中的厂商包 child-first。
- 直接实例化 `java.sql.Driver` 并调用 `connect`，包装为 `DataSource`，不注册全局 `DriverManager`。
- SchemaLoom 只读取 drivers 目录；是否允许 Web 用户上传驱动由集成项目自行决定。
- 文档明确 JDBC JAR 可执行任意代码，集成方必须保证驱动目录可信。

## 数据库 Source、Target 与自动建表

- `JdbcTableSource` 支持表、字段、条件、排序和参数。
- `JdbcQuerySource` 支持复杂参数化 SELECT，并从 `ResultSetMetaData` 获取输出 Schema。
- 数据库目标表不存在时必须自动创建。
- `APPEND`：表不存在则创建；存在则校验字段和类型兼容后追加。
- `REPLACE`：显式删除已有表，再根据输入 Schema 重建。
- 写入每个 batch 使用一个短事务，成功立即提交；任务整体不使用长事务。
- 默认 `batchSize=1000`、`fetchSize=1000`，使用 JdbcTemplate 流式回调和 `batchUpdate`。
- `DatabaseDialect` 是包内接口，不作为公共扩展 API。
- 使用 `EnumMap<DatabaseType, DatabaseDialect>` 保存 MySQL、Oracle、SQL Server 实现。
- 方言只负责标识符引用、逻辑类型映射、建表、删表和 INSERT SQL。
- v1 DDL 复制字段、长度、精度、scale、nullable 和主键。
- 不复制默认表达式、identity、注释、普通索引、外键及检查约束。
- 只有所有主键字段都存在于目标 Schema 时才创建主键。
- 实现 DDL 前先读取用户提供的老项目只读路径，提炼方言规则和测试；不直接复制许可证不明代码。

## CSV 与 XLSX

- CSV 默认 UTF-8、逗号分隔、首行为标题；编码、分隔符、quote、escape 和 header 行可配置。
- CSV 全程流式读取和写入，支持 APPEND、REPLACE。
- CSV 追加时校验已有标题与目标 Schema 一致。
- 仅支持 `.xlsx`，不支持旧 `.xls`。
- XLSX 使用 Hutool SAX 读取，读取一个指定 sheet。
- XLSX 使用 `BigExcelWriter` 流式写入，仅支持 REPLACE。
- 超出单 Sheet 行数时自动创建下一 Sheet。
- 文件 Schema 显式配置优先；未提供时采样前 1000 行推断。
- 推断过程中空值不参与；整数可拓宽为 DECIMAL，日期可拓宽为 TIMESTAMP，不兼容类型最终变为 STRING。
- 带前导零的数字保持 STRING。
- 空标题和重复标题直接失败，不静默重命名。
- REPLACE 文件先写入 `.part`，成功后替换目标文件；失败时保留 `.partial` 并在结果中返回路径。

## 错误处理与结果

支持三种策略：

```text
FAIL_FAST
SKIP_BATCH
ISOLATE_AND_CONTINUE
```

- 默认 `ISOLATE_AND_CONTINUE`。
- 批次写入失败时回滚当前批次，再逐行写入以定位坏数据。
- `EtlResult` 状态为 `SUCCESS`、`PARTIAL`、`FAILED` 或 `CANCELLED`。
- 结果包含读取、转换、过滤、写入、失败数量、开始/结束时间、耗时和错误摘要。
- 最多保留 100 条错误摘要。
- 默认只记录行号、阶段、异常类型、SQLState 和脱敏消息，不保存完整记录内容。
- v1 不实现自动重试、断点恢复、暂停和完整生命周期 Listener。

## 运行与并发

- `EtlTask.run()` 或 `call()` 可同步执行。
- 后台程序可以直接提交给自定义 `ExecutorService`。
- Web 项目可以在 Spring `@Scheduled` 中创建并提交任务，SchemaLoom 不依赖 Spring 调度。
- 提供可选 `LocalTaskExecutor`，内部使用有界 `ThreadPoolExecutor`。
- 单任务单线程，读取和写入在同一线程按 batch 顺序执行，天然具备背压。
- 默认线程数 `max(1, min(availableProcessors, 4))`，默认队列长度 100。
- 队列满时抛出明确的拒绝异常，不在调用线程执行大型任务。
- 并发数、队列大小和拒绝策略可配置。
- 每个库到库任务最多持有一个源连接和一个目标连接。
- 每批检查线程中断，支持通过 `Future.cancel(true)` 在批次边界取消。

## 测试与验收

- 单元测试覆盖数据类型、Schema 推断、字段映射、批次边界、三种错误策略和资源释放。
- 使用内存 Source/Target 覆盖库到库、库到文件、文件到库、文件到文件。
- 构造两个包含同名驱动类的测试 JAR，验证 ClassLoader 隔离、版本选择、缓存和释放。
- 元数据契约测试覆盖三种数据库的 catalog/schema、表、列、主外键和索引。
- 集成测试覆盖 MySQL 5/8 驱动并存、三种数据库自动建表和跨库复制。
- CSV/XLSX 测试覆盖空文件、重复标题、类型推断、追加、覆盖和多 Sheet。
- 增加非默认百万行性能测试，确认内存占用不随总行数线性增长。
- 依赖检查确保没有 Spring Boot/Context、日志实现和厂商 JDBC 驱动进入运行时依赖。
- README 提供四种 ETL 示例、驱动目录格式、自定义线程池和 Spring 调度示例。

## 实施顺序

1. 创建单模块工程、数据模型、三个核心接口和任务 Builder。
2. 实现 `JdbcDriverLoader` 和多版本驱动隔离。
3. 实现 SchemaCrawler 元数据 API。
4. 审计老项目 DDL 参考代码，实现三种内部数据库方言。
5. 实现 JDBC Source/Target、自动建表和批量提交。
6. 实现 CSV/XLSX Source/Target 和类型推断。
7. 实现错误策略、结果统计、本地线程池和取消。
8. 完成组合测试、数据库集成测试、性能测试和项目文档。
