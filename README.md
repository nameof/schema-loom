# SchemaLoom

SchemaLoom 是一个面向 Java 应用的轻量级、可嵌入式批量 ETL 库。它把数据库表、参数化查询、CSV、XLSX 和内存数据统一为 `Source` / `Target`，通过类型化 Schema、可组合转换和可观测的任务结果完成数据迁移与文件交换。

SchemaLoom 不依赖 Spring Boot、Web 容器或任务调度框架，适合命令行工具、后台服务、定时任务和测试代码。它是一个库，不是独立运行的 ETL 服务，也不负责替集成项目管理凭据、权限和任务编排。

> 当前版本：`0.1.0-SNAPSHOT`<br>
> Java：8 及以上（项目当前以 Java 8 编译）<br>
> License：Apache-2.0

## 目录

- [特性](#特性)
- [工作方式](#工作方式)
- [安装](#安装)
- [快速开始](#快速开始)
- [Quickstart：内存到内存](#quickstart内存到内存)
- [Quickstart：数据库到数据库](#quickstart数据库到数据库)
- [Quickstart：文件到数据库](#quickstart文件到数据库)
- [Quickstart：数据库到文件](#quickstart数据库到文件)
- [转换、映射与错误处理](#转换映射与错误处理)
- [数据库元数据](#数据库元数据)
- [JDBC 驱动接入](#jdbc-驱动接入)
- [并发执行与取消](#并发执行与取消)
- [扩展接入](#扩展接入)
- [测试](#测试)
- [功能规划](#功能规划)
- [安全边界](#安全边界)
- [贡献与许可](#贡献与许可)

## 特性

### 统一的批量 ETL API

- `EtlTask` 负责读取、转换、字段映射和写入，默认按批次处理。
- `Source` 与 `Target` 都是普通 Java 接口，可直接嵌入现有应用。
- 任务实现 `Callable<EtlResult>`，既可以同步执行，也可以提交到调用方自己的 `ExecutorService`。
- Source、Target 在任务结束时统一关闭，结果包含状态、计数、耗时和错误摘要。

### 多种数据源与目标

| 类型 | Source | Target | 说明 |
| --- | --- | --- | --- |
| 内存 | `MemorySource` | `MemoryTarget` | 单元测试、二次处理和示例 |
| JDBC | `JdbcTableSource`、`JdbcQuerySource` | `JdbcTableTarget` | 表读取、参数化查询、批量写入 |
| CSV | `CsvSource` | `CsvTarget` | UTF-8、标题行、分隔符和 Schema 推断 |
| XLSX | `XlsxSource` | `XlsxTarget` | 指定 Sheet、流式读取和多 Sheet 写入 |

数据库方言当前覆盖 MySQL、Oracle 和 SQL Server。数据库目标支持 `APPEND` 与 `REPLACE`，目标表不存在时会依据输入 Schema 建表。

### 类型化 Schema 与转换

内置逻辑类型包括 `BOOLEAN`、`INT16`、`INT32`、`INT64`、`DECIMAL`、`FLOAT32`、`FLOAT64`、`STRING`、`DATE`、`TIME`、`TIMESTAMP` 和 `BINARY`。金额等精确数值使用 `BigDecimal`，不会默认降级为 `Double`。

- `Transformer` 可保留、改写或丢弃记录。
- `FieldMapping` 支持字段选择、重命名和顺序调整。
- CSV/XLSX 在未显式提供 Schema 时可以从样本推断；需要稳定契约时建议显式指定 Schema。

### 可控的失败策略

支持 `FAIL_FAST`、`SKIP_BATCH`、`ISOLATE_AND_CONTINUE` 三种策略。建议在 Builder 中显式设置策略；使用 `ISOLATE_AND_CONTINUE` 时，批次失败会回退到逐行写入，尽可能定位坏数据并继续处理。

`EtlResult` 的状态为 `SUCCESS`、`PARTIAL`、`FAILED` 或 `CANCELLED`；错误摘要最多保留 100 条，并对常见密码字段做脱敏处理。

### 元数据与 JDBC 驱动隔离

- `DatabaseMetadataService` 提供数据库、Catalog、Schema、表、列、主键、外键、索引和注释等中立 DTO。
- `JdbcDriverLoader` 可从受控目录加载驱动，支持多个驱动描述文件、优先级、URL 前缀和服务端版本范围。
- 驱动使用独立 ClassLoader，不注册到全局 `DriverManager`，便于同一进程中隔离不同版本的驱动。

## 工作方式

每个任务遵循固定的数据流：

```text
Source.schema()
    -> 目标 Schema（可选字段映射）
    -> Target.prepare(schema, targetMode)
    -> 分批读取
    -> Transformer
    -> FieldMapping
    -> Target.write(batch)
    -> EtlResult
```

单个任务在一个执行线程中按批次顺序处理，天然具备背压；数据库写入以批次提交事务，不持有覆盖整个任务的长事务。

## 安装

项目当前处于 `0.1.0-SNAPSHOT` 开发阶段，使用源码构建：

```bash
# 在仓库根目录执行
mvn test
```

在 Maven 项目中加入依赖（坐标以当前 `pom.xml` 为准）：

```xml
<dependency>
  <groupId>io.github.nameof</groupId>
  <artifactId>schemaloom</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

使用 JDBC 时，应用仍需提供相应数据库驱动。核心构建不绑定 MySQL、Oracle 或 SQL Server 的生产驱动版本。

## 快速开始

以下示例均假定已导入：

```java
import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.engine.EtlTask;
```

### Quickstart：内存到内存

```java
RecordSchema schema = new RecordSchema(Arrays.asList(
    FieldSchema.of("id", LogicalType.INT32),
    FieldSchema.of("name", LogicalType.STRING)
));

List<DataRecord> records = Arrays.asList(
    new DataRecord(schema, Arrays.<Object>asList(1, "alpha")),
    new DataRecord(schema, Arrays.<Object>asList(2, "beta"))
);

MemoryTarget target = new MemoryTarget();
EtlResult result = EtlTask.builder()
    .source(new MemorySource(schema, records, 1000))
    .target(target)
    .transformer(Transformer.identity())
    .errorPolicy(ErrorPolicy.ISOLATE_AND_CONTINUE)
    .targetMode(TargetMode.REPLACE)
    .build()
    .run();

assert result.getStatus() == EtlStatus.SUCCESS;
assert target.getRecords().size() == 2;
```

### Quickstart：数据库到数据库

`JdbcConnectionProvider` 使用运行环境中的 JDBC 驱动和 `DriverManager`。生产环境不要把密码硬编码在源码中。

普通应用可以使用 `DatabaseConnectionInfo` 描述连接和默认 catalog/schema，Source/Target 只需要表名：

```java
DatabaseConnectionInfo database = new DatabaseConnectionInfo(
    DatabaseType.MYSQL, host, port, databaseName,
    catalog, schema, username, password, null, null);
Source source = new JdbcTableSource(database, "source_table");
Target target = new JdbcTableTarget(database, "target_table");
```

需要连接池、测试替身或自定义连接生命周期时，仍可使用 `ConnectionProvider` 和 `QualifiedTableName` 构造器。

```java
DatabaseConnectionInfo sourceDatabase = ...;
DatabaseConnectionInfo targetDatabase = ...;

EtlResult result = EtlTask.builder()
    .source(new JdbcTableSource(sourceDatabase, "source_table", 1000))
    .target(new JdbcTableTarget(targetDatabase, "target_table"))
    .targetMode(TargetMode.REPLACE)
    .errorPolicy(ErrorPolicy.FAIL_FAST)
    .build()
    .run();

if (result.getStatus() != EtlStatus.SUCCESS) {
    throw new IllegalStateException("ETL failed: " + result.getErrors());
}
```

`JdbcTableSource` 读取指定表；需要筛选、联表或聚合时使用参数化 `JdbcQuerySource`：

```java
Source source = new JdbcQuerySource(
    sourceDatabase,
    "SELECT id, name FROM source_table WHERE id >= ?",
    Collections.<Object>singletonList(100),
    1000);
```

### Quickstart：文件到数据库

CSV 默认使用 UTF-8、逗号分隔和标题行。未提供 Schema 时会从最多 1000 行样本推断类型；对金额、标识符和前导零字段，建议显式提供 Schema。

```java
Source source = new CsvSource(Paths.get("input.csv"));
Target target = new JdbcTableTarget(
    new DatabaseConnectionInfo(DatabaseType.MYSQL, dbHost, dbPort, dbName,
        null, null, dbUser, dbPassword, null, null),
    "orders");

EtlResult result = EtlTask.builder()
    .source(source)
    .target(target)
    .targetMode(TargetMode.APPEND)
    .build()
    .run();
```

需要自定义编码、分隔符或标题行时：

```java
Source source = new CsvSource(
    Paths.get("input.tsv"),
    explicitSchema,
    StandardCharsets.UTF_8,
    '\t',
    0);
```

### Quickstart：数据库到文件

CSV 目标支持追加和覆盖；XLSX 目标只支持覆盖，并使用临时文件完成写入后替换目标文件。

```java
Source source = new JdbcQuerySource(
    new JdbcConnectionProvider(dbUrl, dbUser, dbPassword),
    "SELECT id, name, amount FROM orders WHERE created_at >= ?",
    Collections.<Object>singletonList(startTime),
    1000);

Target target = new CsvTarget(Paths.get("orders.csv"));

EtlResult result = EtlTask.builder()
    .source(source)
    .target(target)
    .targetMode(TargetMode.REPLACE)
    .transformer(Transformer.identity())
    .build()
    .run();
```

替换目标为 XLSX：

```java
Target target = new XlsxTarget(Paths.get("orders.xlsx"));
```

XLSX Source 可指定 Sheet；不指定 Schema 时会基于前 1000 行数据推断：

```java
Source source = new XlsxSource(
    Paths.get("input.xlsx"),
    "Sheet1",
    explicitSchemaOrNull);
```

## 转换、映射与错误处理

转换器可以改写字段值，也可以过滤记录。转换器不负责动态修改字段集合；字段集合和字段名变化通过 `FieldMapping` 表达。

```java
Transformer transformer = record -> {
    if (record.get("id") == null) return TransformResult.drop();
    return TransformResult.keep(record.with("name", record.get("name").toString().trim()));
};

List<FieldMapping> mappings = Arrays.asList(
    new FieldMapping("id", "customer_id"),
    new FieldMapping("name", "customer_name"));

EtlResult result = EtlTask.builder()
    .source(source)
    .target(target)
    .transformer(transformer)
    .mappings(mappings)
    .errorPolicy(ErrorPolicy.ISOLATE_AND_CONTINUE)
    .build()
    .run();
```

策略选择建议：

| 策略 | 行为 | 适用场景 |
| --- | --- | --- |
| `FAIL_FAST` | 首个转换或写入错误即结束任务 | 强一致迁移、上线校验 |
| `SKIP_BATCH` | 丢弃当前批次并继续 | 可接受批量丢弃的导入 |
| `ISOLATE_AND_CONTINUE` | 批量写入失败后逐行定位并继续 | 尽量处理有效数据并收集坏行 |

始终检查 `EtlResult.getStatus()`，不要只根据 `getWritten()` 判断任务成功。

## 数据库元数据

```java
DatabaseMetadataService metadata = new DatabaseMetadataService();
TableInfo table = metadata.getTable(
    connectionProvider,
    new QualifiedTableName(null, null, "orders"));

System.out.println(table.getColumns());
System.out.println(table.getPrimaryKey());
```

可用操作包括 `getDatabaseInfo`、`listCatalogs`、`listSchemas`、`listTables` 和 `getTable`。SchemaLoom 将底层 SchemaCrawler 类型映射为自己的 DTO，业务代码无需直接依赖其模型。

## JDBC 驱动接入

### 直接使用运行时驱动

驱动已在应用 classpath 时，可使用 `JdbcConnectionProvider`：

```java
ConnectionProvider provider =
    new JdbcConnectionProvider(url, user, password);
```

### Quickstart：管理自定义 JDBC 驱动

`JdbcDriverLoader` 无参构造默认读取 classpath 下的 `drivers` 资源目录，对应源码目录 `src/main/resources/drivers`。每个 `.properties` 文件描述一个驱动，`classpath` 只能引用该目录内的 JAR：

目录结构：

```text
drivers/
  mysql8.properties
  mysql-connector-j-8.0.28.jar
```

```properties
id=mysql8
databaseType=MYSQL
driverClass=com.mysql.cj.jdbc.Driver
classpath=mysql-connector-java-8.0.28.jar
urlPrefixes=jdbc:mysql:
urlTemplate=jdbc:mysql://${host}:${port}/${database}
priority=20
serverVersionRange=[5.7,9.0)
driverPackages=com.mysql.cj,com.mysql
defaultProperties=useSSL=false;serverTimezone=UTC;connectTimeout=1000
```

应用代码显式创建 Loader。`connect` 会读取 descriptor、按 `driverId` 选择驱动、创建隔离 ClassLoader，并返回 `ConnectionProvider`：

```java
JdbcDriverLoader loader = new JdbcDriverLoader();
Properties connectionProperties = new Properties();
connectionProperties.setProperty("useSSL", "false");
DatabaseConnectionInfo sourceConfig = new DatabaseConnectionInfo(DatabaseType.MYSQL,
    "localhost", 3306, "source_db", System.getenv("MYSQL_USER"),
    System.getenv("MYSQL_PASSWORD"), null, connectionProperties); // null: 按 priority 自动选择
DatabaseConnectionInfo targetConfig = new DatabaseConnectionInfo(DatabaseType.MYSQL,
    "localhost", 3306, "target_db", System.getenv("MYSQL_USER"),
    System.getenv("MYSQL_PASSWORD"), "mysql8", connectionProperties); // 显式选择版本
ConnectionProvider sourceConnection = loader.connect(sourceConfig);
ConnectionProvider targetConnection = loader.connect(targetConfig);

try {
    EtlResult result = EtlTask.builder()
        .source(new JdbcTableSource(sourceConfig, "source_table", loader, 1000))
        .target(new JdbcTableTarget(targetConfig, "target_table", loader))
        .targetMode(TargetMode.REPLACE)
        .errorPolicy(ErrorPolicy.FAIL_FAST)
        .build()
        .run();
    if (result.getStatus() != EtlStatus.SUCCESS) {
        throw new IllegalStateException("ETL failed: " + result.getErrors());
    }
} finally {
    // EtlTask 会关闭 Source/Target 持有的 provider；loader 最后关闭。
    loader.close();
}
```

不指定 `driverId` 时可以使用自动模式：Loader 按 `databaseType`、URL 前缀和 `priority` 选择候选驱动，并在连接成功后校验 `serverVersionRange`。显式指定 `driverId` 时不会回退到其他驱动。

驱动 JAR 本质上可以执行任意代码。`drivers` 目录必须是集成环境信任和可控的目录，不应直接允许 Web 用户上传并加载驱动。

## 并发执行与取消

SchemaLoom 不在 `EtlTask` 内部创建线程。可以使用应用自己的线程池，或使用内置的有界 `LocalTaskExecutor`：

```java
try (LocalTaskExecutor executor = new LocalTaskExecutor(4, 100)) {
    Future<EtlResult> future = executor.submit(task);
    EtlResult result = future.get();
}
```

调用 `Future.cancel(true)` 后，任务会在批次边界检查中断并返回 `CANCELLED`。队列满时会抛出明确的拒绝异常。调度、重试、超时和任务持久化由宿主应用负责。

## 扩展接入

新增数据格式时，实现普通 `Source` 或 `Target` 即可：

```java
public final class MySource implements Source {
    public RecordSchema schema() { /* 返回稳定 Schema */ }
    public void read(BatchConsumer consumer) { /* 分批产生 RecordBatch */ }
    public void close() { /* 释放资源 */ }
}
```

扩展实现应遵守以下约定：

- `RecordBatch` 中的记录必须使用同一个 `RecordSchema`。
- `Target.prepare` 必须先于 `write` 调用。
- `write` 返回实际成功和失败的数量；不可恢复的错误通过异常报告。
- `close` 应可重复调用，并释放连接、文件句柄等资源。
- 不需要注册 SPI、`ServiceLoader` 或框架上下文。

## 测试

运行默认单元测试：

```bash
mvn test
```

运行 MySQL 集成测试前，准备可写的测试数据库。测试用例约定的环境变量如下：

```powershell
$env:SCHEMALOOM_IT_MYSQL_HOST = "localhost"
$env:SCHEMALOOM_IT_MYSQL_PORT = "3306"
$env:SCHEMALOOM_IT_MYSQL_DATABASE = "test"
$env:SCHEMALOOM_IT_MYSQL_USER = "test"
$env:SCHEMALOOM_IT_MYSQL_PASSWORD = "<password>"
# 可选：让集成测试通过 JdbcDriverLoader 加载自定义驱动
$env:SCHEMALOOM_IT_MYSQL_DRIVER_ID = "mysql8"
mvn -Dtest=JdbcEtlIntegrationTest test
```

不要把真实凭据提交到仓库。配置 `SCHEMALOOM_IT_MYSQL_DRIVER_ID` 后，测试会从 classpath 的 `drivers` 资源目录加载 descriptor 和 JAR，并通过 `JdbcDriverLoader.connect(...)` 创建源连接、目标连接和校验连接。集成测试会创建并清理 `schemaloom_source`、`schemaloom_target` 测试表。

## 功能规划

以下内容属于规划，不代表当前版本已经实现：

1. 完善 CSV/XLSX 的边界测试、百万行内存占用测试和更多数据库契约测试。
2. 增加 Oracle、SQL Server 的真实集成验证，以及更多 JDBC 驱动共存场景。
3. 补充自动重试、断点恢复、暂停和完整生命周期 Listener。
4. 增强 Schema 演进、默认值、identity、注释、索引、外键和检查约束的复制能力。
5. 发布稳定版本和版本化 API 文档，并评估 Java 17、SchemaCrawler 17 与 Spring JDBC 6 的升级。

详细的当前实施记录见 [`plan.md`](plan.md)，Java 17 升级说明见 [`MIGRATING_TO_JAVA_17.md`](MIGRATING_TO_JAVA_17.md)。

## 安全边界

- 连接字符串、用户名和密码由宿主应用提供；不要在日志、源码或提交记录中保存密钥。
- `JdbcQuerySource` 使用参数列表传值，不要通过字符串拼接构造用户输入的 SQL。
- 表名、Schema 名和驱动目录属于配置边界，应在进入任务前完成白名单校验。
- 错误消息会做有限脱敏，但集成方仍应审查日志与异常上报链路。
- 使用 `JdbcDriverLoader` 时，只加载经过审计、来源可信的驱动 JAR。

## 贡献与许可

提交 Issue 或 Pull Request 前，请先确认改动范围、测试方式和兼容性影响。新增行为应补充对应测试，文档中的示例应与公开 API 保持一致。

本项目以 [Apache License 2.0](LICENSE) 发布。

## 关于catalog/schema
不同数据库对“库、模式、表”的定义不同：

| 数据库 | catalog | schema | 示例 |
|---|---|---|---|
| MySQL | 数据库名 | 通常为空 | `shop.orders` |
| Oracle | 通常为空 | 用户/模式 | `APP.ORDERS` |
| SQL Server | 数据库名 | 如 `dbo` | `shop.dbo.orders` |


## 核心功能&BUG TODO
- 当前LogicalType对应的实际数据未规范化，例如同样的时间LogicalType在mysql可能返回LocalDate、oracle可能返回厂商专用对象、XLSX可能返回long。 尤其是异库脱敏可能直接报错
- 目前的驱动加载仅适合开发测试：实际打包运行的路径为application.jar!/drivers/mysql8.properties，URLClassLoader无法加载，生产环境需要调整代码为外部路径加载。  （但我就想用这种方式管理驱动，那就重写类加载器？SPI? ）
- 使用SchemaCrawler操作数据库，并封装实体类，不对外暴露SchemaCrawler API
- 索引、字段默认值、自增、注释复制
- 明确区分 TABLE 和 VIEW，视图是只读对象，不能按普通表处理；可支持复制视图
- 允许视图作为 Source，视图不参与目标建表逻辑
- 外键迁移
- BLOB等二进制字段：直接跳过；或流式读写，用户可选择忽略（默认）这种字段
- TEXT/LONGTEXT ：用户可选择忽略
- CSV 配置：自定义quote、escape
- 日志输出
- XLSX指定sheet
- 代码审计：所有逻辑均正确关闭已打开资源

## 优化TODO
- loader、provider、connection、source、target 、task，一环套一环，需简化资源释放和引用关系
- 资源配额：连接、内存、线程、文件限制

## 低优先级TODO
- 冲突处理：目标表已存在主键冲突的数据时，可选策略：忽略（ISOLATE_AND_CONTINUE已支持）、失败（FAIL_FAST已支持）、更新/覆盖（TODO）
- 临时表/分区表/存储过程/函数/触发器/sequence，明确短期不支持
- 其他数据源支持：PGSQL、MONGO、JSON
- JDBC Source：指定部分字段、参数化WHERE过滤条件配置（包含增量脱敏功能）
