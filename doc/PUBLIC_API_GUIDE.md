# SchemaLoom 对外 API 调用测试文档

本文面向需要在 Java 代码中调用 SchemaLoom 的同事，当前覆盖两个核心入口：

- `io.github.nameof.schemaloom.metadata.DatabaseMetadataService`：读取数据库元数据。
- `io.github.nameof.schemaloom.engine.EtlTask`：执行批量读取、转换、映射和写入。

当前项目版本：`0.1.0-SNAPSHOT`。项目是嵌入式 Java 库，不提供 HTTP 服务端点；以下“API”均指 Java 类和方法。

## 1. 基础准备

### 1.1 Maven 依赖

```xml
<dependency>
  <groupId>io.github.nameof</groupId>
  <artifactId>schemaloom</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

使用 JDBC 时，还需要提供对应数据库驱动。项目当前支持 `MYSQL`、`ORACLE`、`SQL_SERVER`，驱动可通过 `JdbcDriverLoader` 从受控的 `drivers` 目录加载。

### 1.2 创建数据库连接配置

```java
DatabaseConnectionInfo config = new DatabaseConnectionInfo(
    DatabaseType.MYSQL,
    System.getenv("DB_HOST"),
    Integer.parseInt(System.getenv("DB_PORT")),
    System.getenv("DB_NAME"),
    null,                         // catalog
    System.getenv("DB_SCHEMA"),  // schema，可为 null
    System.getenv("DB_USER"),
    System.getenv("DB_PASSWORD"),
    null,                         // driverId，null 表示按优先级自动选择
    null                          // JDBC properties
);

JdbcDriverLoader loader = new JdbcDriverLoader();
ConnectionProvider provider = loader.connect(config);
```

不要在源码、提交记录或文档中写入真实密码。`ConnectionProvider` 持有一个 JDBC 连接，不是连接池；使用完毕后关闭 `provider`，最后关闭 `loader`。

### 1.3 用例：测试数据库连接

连接测试至少应同时验证 JDBC 连接和元数据读取，避免只验证“连接对象创建成功”而忽略权限或 Schema 配置问题：

```java
JdbcDriverLoader loader = new JdbcDriverLoader();
ConnectionProvider provider = null;
try {
    provider = loader.connect(config);

    // 验证连接可用
    boolean valid = provider.getConnection().isValid(3);
    if (!valid) {
        throw new IllegalStateException("数据库连接不可用");
    }

    // 验证 SchemaLoom 能读取数据库元数据
    DatabaseInfo info = new DatabaseMetadataService().getDatabaseInfo(provider);
    System.out.println(info.getProductName() + " " + info.getProductVersion());
} finally {
    if (provider != null) provider.close();
    loader.close();
}
```

`isValid(3)` 的参数单位为秒；如果目标 JDBC 驱动不支持该方法，可直接调用 `getDatabaseInfo(provider)` 作为连接和元数据权限的综合测试。测试失败时检查数据库地址、端口、账号权限、驱动描述文件和默认 Catalog/Schema。

## 2. DatabaseMetadataService

### 2.1 方法总览

| 方法 | 返回值 | 说明 |
| --- | --- | --- |
| `getDatabaseInfo(provider)` | `DatabaseInfo` | 数据库产品、版本、驱动和 JDBC URL |
| `listCatalogs(provider)` | `List<CatalogInfo>` | 列出 Catalog |
| `listSchemas(provider)` | `List<SchemaInfo>` | 列出 Schema，结果同时包含 Catalog |
| `listTables(provider, query)` | `List<TableInfo>` | 按 Catalog、Schema、表名模式筛选表和视图 |
| `getTable(provider, name)` | `TableInfo` | 获取单个表或视图，不存在时抛 `SchemaLoomException` |

### 2.2 元数据查询示例

```java
DatabaseMetadataService metadata = new DatabaseMetadataService();

DatabaseInfo database = metadata.getDatabaseInfo(provider);
System.out.println(database.getProductName());

List<SchemaInfo> schemas = metadata.listSchemas(provider);
List<TableInfo> tables = metadata.listTables(
    provider,
    new MetadataQuery(null, config.getSchema(), "order%"));

TableInfo orders = metadata.getTable(
    provider,
    new QualifiedTableName(null, config.getSchema(), "orders"));

for (ColumnInfo column : orders.getColumns()) {
    System.out.println(column.getName() + " -> " + column.getLogicalType());
}
```

`MetadataQuery` 的表名模式默认是 `%`；当前实现按大小写不敏感方式匹配，并将 `%` 视为任意字符、`_` 视为单个字符。`TableInfo` 还提供 `getPrimaryKey()`、`getForeignKeys()`、`getIndexes()`、`getConstraints()`、`getRemarks()` 和 `isView()` 等结构信息。

### 2.3 生命周期和异常

```java
try {
    TableInfo table = new DatabaseMetadataService().getTable(
        provider, new QualifiedTableName(null, config.getSchema(), "orders"));
} finally {
    provider.close();
    loader.close();
}
```

元数据读取失败会包装为 `SchemaLoomException`。同一个 `ConnectionProvider` 不应被多个并发任务共享，除非调用方自行保证所有 JDBC 操作串行化。

## 3. EtlTask

### 3.1 构建和执行

`EtlTask.builder()` 必须设置 `source` 和 `target`。其他参数均有默认值：

| Builder 方法 | 默认值 | 作用 |
| --- | --- | --- |
| `source(Source)` | 无 | 数据来源，必填 |
| `target(Target)` | 无 | 写入目标，必填 |
| `transformer(Transformer)` | `null` | 保留、修改或丢弃记录 |
| `mappings(List<FieldMapping>)` | 空列表 | 选择、重命名和调整字段顺序 |
| `errorPolicy(ErrorPolicy)` | `ISOLATE_AND_CONTINUE` | 转换或写入失败处理策略 |
| `targetMode(TargetMode)` | `APPEND` | 目标表追加或替换 |
| `context(Object)` | `null` | 传给监听器的上下文对象 |
| `listener(EtlTaskListener)` | `null` | 生命周期回调 |
| `listenerErrorHandler(ListenerErrorHandler)` | `null` | 监听器异常处理 |

数据库表到数据库表的最小示例：

```java
EtlTask task = EtlTask.builder()
    .source(new JdbcTableSource(config, "source_table", loader, 1000))
    .target(new JdbcTableTarget(config, "target_table", loader))
    .targetMode(TargetMode.REPLACE)
    .errorPolicy(ErrorPolicy.FAIL_FAST)
    .build();

EtlResult result = task.run();
if (result.getStatus() != EtlStatus.SUCCESS) {
    throw new IllegalStateException("ETL failed: " + result.getErrors());
}
```

`JdbcTableSource` 支持表和视图读取；`JdbcQuerySource` 仅允许参数化 `SELECT`。目标表不存在时，`JdbcTableTarget.prepare` 会按输入 Schema 建立普通表；目标是视图或结构不兼容时会失败。

### 3.2 转换和字段映射

```java
EtlTask task = EtlTask.builder()
    .source(source)
    .target(target)
    .mappings(Arrays.asList(
        new FieldMapping("user_id", "id"),
        new FieldMapping("user_name", "name")))
    .transformer(record -> {
        if (record.get("name") == null) return TransformResult.drop();
        return TransformResult.keep(record);
    })
    .build();
```

映射列表为空时保留全部源字段及原顺序；提供映射后，映射列表决定目标字段集合和顺序。映射目标字段不能重复，源字段必须存在，否则构建或运行阶段抛出异常。

### 3.3 结果和错误处理

`EtlResult` 主要字段如下：

| 字段 | 含义 |
| --- | --- |
| `getStatus()` | `SUCCESS`、`PARTIAL`、`FAILED`、`CANCELLED` |
| `getRead()` | 已读取记录数 |
| `getTransformed()` | 转换并进入写入流程的记录数 |
| `getFiltered()` | 被 Transformer 丢弃的记录数 |
| `getWritten()` | 成功写入记录数 |
| `getFailed()` | 失败记录数 |
| `getElapsedMillis()` | 执行耗时 |
| `getErrors()` | 最多 100 条错误摘要 |

状态判断必须使用 `getStatus()`，不能只根据 `getWritten()` 判断成功。`EtlError` 提供错误行号、阶段、异常类型和消息；消息会截断到 500 个字符，并对常见密码参数做脱敏。

错误策略：

- `FAIL_FAST`：首个转换或写入错误即结束任务。
- `SKIP_BATCH`：跳过当前批次并继续后续批次。
- `ISOLATE_AND_CONTINUE`：批量写入失败时回退到逐行写入，尽可能继续处理。

### 3.4 进度监听和异步执行

监听器回调在任务线程同步执行：

```java
EtlTask task = EtlTask.builder()
    .source(source)
    .target(target)
    .listener(new EtlTaskListener() {
        public void onProgress(Object context, EtlProgress progress) {
            System.out.println(progress.getRead() + "/" + progress.getTotal());
        }
    })
    .build();
```

需要异步执行时可使用 `LocalTaskExecutor`：

```java
try (LocalTaskExecutor executor = new LocalTaskExecutor(2, 20)) {
    Future<EtlResult> future = executor.submit(task);
    EtlResult result = future.get();
}
```

调用 `Future.cancel(true)` 后，任务会在批次边界检查中断，并返回 `CANCELLED`。任务结束时会关闭 `Source` 和 `Target`；使用 `JdbcTableSource`/`JdbcTableTarget` 时不要再关闭它们内部的连接，也不要让多个并发任务共享同一个 provider。

### 3.5 用例：仅读取指定表的 1000 条样本

`JdbcTableSource` 会读取整张表；它的 `fetchSize` 只控制每批读取数量，不限制总行数。需要抽样读取时，使用 `JdbcQuerySource` 在 SQL 层限制行数：

```java
String sampleSql = "SELECT * FROM orders ORDER BY id LIMIT 1000"; // MySQL
JdbcQuerySource sampleSource = new JdbcQuerySource(
    config, sampleSql, Collections.emptyList(), 200, loader);
MemoryTarget sampleTarget = new MemoryTarget();

EtlResult result = EtlTask.builder()
    .source(sampleSource)
    .target(sampleTarget)
    .targetMode(TargetMode.REPLACE)
    .errorPolicy(ErrorPolicy.FAIL_FAST)
    .build()
    .run();

if (result.getStatus() != EtlStatus.SUCCESS) {
    throw new IllegalStateException("抽样读取失败: " + result.getErrors());
}
if (sampleTarget.getRecords().size() > 1000) {
    throw new IllegalStateException("抽样结果超过 1000 条");
}
```

不同数据库的行数限制语法不同，示例中的 SQL 需要按数据库类型替换：

| 数据库 | 示例 |
| --- | --- |
| MySQL | `SELECT * FROM orders ORDER BY id LIMIT 1000` |
| Oracle | `SELECT * FROM orders ORDER BY id FETCH FIRST 1000 ROWS ONLY` |
| SQL Server | `SELECT TOP (1000) * FROM orders ORDER BY id` |

`JdbcQuerySource` 仅允许 `SELECT` 语句；表名和排序字段应来自受信任配置，不能直接拼接外部用户输入。建议使用稳定且有索引的字段排序，否则每次抽样的结果可能变化。若表不足 1000 条，实际结果会少于 1000 条。

## 4. 建议的调用测试清单

1. 元数据：验证数据库信息、Catalog/Schema 列表、表模式筛选、单表列和主键读取。
2. ETL 成功：验证 `SUCCESS`、读取数、写入数和目标结构。
3. 字段映射：验证重命名、字段选择、顺序变化和主键映射。
4. 异常策略：分别验证三种 `ErrorPolicy` 及 `PARTIAL`/`FAILED` 状态。
5. 资源释放：验证正常结束、异常结束和取消后连接均已释放。
6. 安全：连接参数从环境变量或密钥管理系统注入，不在日志输出密码。
