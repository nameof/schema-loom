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

使用 JDBC 时，还需要提供对应数据库驱动。项目当前支持 `MYSQL`、`ORACLE`、`SQL_SERVER`、`POSTGRESQL`，驱动可通过 `JdbcDriverLoader` 从受控的 `drivers` 目录加载。

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

不要在源码、提交记录或文档中写入真实密码。`ConnectionProvider` 持有一个 JDBC 连接，不是连接池；使用完毕后关闭 `provider`，最后关闭 `loader`。内部执行适配层只借用该连接，不拥有连接生命周期。连接关闭失败会抛出 `SchemaLoomException`，在连接仍未关闭时保留 Provider 引用以便再次关闭；存在活动连接时禁止提前关闭 Loader。

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
| `listSchemas(provider)` | `List<SchemaInfo>` | 列出当前连接可见的 Schema，结果同时包含 Catalog |
| `listSchemas(provider, query)` | `List<SchemaInfo>` | 按 Catalog、Schema 限定采集范围后列出 Schema |
| `listTables(provider, query)` | `List<TableInfo>` | 按 Catalog、Schema、表名模式筛选表和视图 |
| `getTable(provider, name)` | `TableInfo` | 获取单个表或视图，不存在时抛 `SchemaLoomException` |
| `getTableStatistics(provider, name)` | `TableStatistics` | 读取单表系统统计，不执行 `COUNT(*)` |
| `getSchemaStatistics(provider, schema)` | `SchemaStatistics` | 读取 Schema 普通表聚合统计 |

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

字段模型分为两层：`FieldSchema` 表示跨数据源通用的字段信息；`ColumnInfo` 表示数据库列，并额外包含原生类型名、标准 JDBC 类型号、注释、默认值、自增和生成列信息。`LogicalType` 只表示跨库数据语义，不能反推出源列的 JDBC 类型。`TableInfo` 只保存 `ColumnInfo`，其 `toRecordSchema()` 从列定义派生出不含数据库约束的 `RecordSchema`；主键、索引、外键和约束只保留在 `TableInfo` 中。非数据库数据源只需提供 `RecordSchema`。

统计 API 使用数据库系统目录，行数可能是近似值或依赖最近一次统计；长度单位为字节，Schema 的总数据长度不包含索引。统计字段缺失时返回 `0`，原因可能是真实值为零、统计尚未收集、存储引擎不提供或当前账号没有对应系统视图权限。

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

`JdbcTableSource` 支持表和视图读取；`JdbcQuerySource` 仅允许参数化 `SELECT`。目标表不存在时，`JdbcTableTarget.prepare` 会按输入 Schema 建立普通表；目标是视图或结构不兼容时会失败。`REPLACE` 模式先写入 `<目标表>_tmp`，写入成功后 rename 切换正式表，失败时清理临时表并保留原目标表。

JDBC Source、Target 和视图迁移在内部使用 Spring JDBC 执行适配层管理参数绑定、语句与结果集释放、异常转换；`JdbcTableTarget` 的每个写入批次使用独立短事务。该实现细节不出现在公共 API 中，LOB 的 `STREAM` 直传尚未开放。

### 3.2 JDBC 大字段策略

大字段策略只配置在 JDBC Source，`Target` 和 `EtlTask` 无需判断数据库大字段类型。默认仅跳过
`BLOB`，而 `CLOB`、`TEXT`、`LONGTEXT` 默认复制；被跳过字段仍保留在 Schema 中，读取值为
`null`，目标表也保留该列。

不需要迁移超长数据时，可使用 `LargeFieldPolicy.skipAllLargeFields()` 跳过标准二进制类型和长文本类型，
普通短文本仍会复制。

```java
LargeFieldPolicy policy = LargeFieldPolicy.builder()
    .typeName("LONGTEXT", LargeFieldPolicy.Action.SKIP)
    .field("attachment", LargeFieldPolicy.Action.SKIP)
    .maxTextChars(1024 * 1024)
    .maxBinaryBytes(8L * 1024 * 1024)
    .build();

JdbcTableSource source = new JdbcTableSource(config, "source_table", loader, 1000, policy);
```

字段规则优先于 JDBC 类型和原生类型规则。超过文本或二进制阈值的值会写为 `null`，并记入读取统计。
策略可能产生 `null` 的字段会作为可空字段传给目标；若追加目标的对应列为 `NOT NULL`，准备阶段会失败。
当前支持 `COPY`、`SKIP`；真正的 JDBC 流式复制需要保持 ResultSet 与目标绑定的同一生命周期，尚未开放。

### 3.3 转换和字段映射

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

### 3.4 结果和错误处理

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
| `getReadStatistics()` | 读取行数、读取阶段跳过行数和按字段跳过计数 |
| `getWriteStatistics()` | 写入行数、写入阶段跳过行数、失败行数和按字段跳过计数 |
| `getTotalSkippedRows()` | 读取与写入阶段明确跳过整行的总数，不含仅置空字段 |

状态判断必须使用 `getStatus()`，不能只根据 `getWritten()` 判断成功。`EtlError` 提供错误行号、阶段、异常类型和消息；消息会截断到 500 个字符，并对常见密码参数做脱敏。

错误策略：

- `FAIL_FAST`：首个转换或写入错误即结束任务。
- `SKIP_BATCH`：跳过当前批次并继续后续批次。
- `ISOLATE_AND_CONTINUE`：批量写入失败时回退到逐行写入，尽可能继续处理。

### 3.5 进度监听和异步执行

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

### 3.6 用例：读取表预览数据

`JdbcTableSource` 提供不暴露 SQL 的预览入口：

```java
JdbcTableSource tableSource = new JdbcTableSource(config, "orders");
List<DataRecord> sample = tableSource.preview(50); // 允许 1 到 300 行
```

预览结果不保证稳定顺序，数据不足时返回实际行数；为避免读取 BLOB/BINARY 大字段，二进制列在预览结果中保留但值为 `null`。`read(BatchConsumer)` 仍读取全表，`count()` 仍返回全表总数。限制通过 JDBC `setMaxRows` 执行，不支持 offset、分页或排序。若需要筛选、联表或聚合，继续使用参数化 `JdbcQuerySource`。

## 4. 建议的调用测试清单

1. 元数据：验证数据库信息、Catalog/Schema 列表、表模式筛选、单表列和主键读取。
2. ETL 成功：验证 `SUCCESS`、读取数、写入数和目标结构。
3. 字段映射：验证重命名、字段选择、顺序变化和主键映射。
4. 异常策略：分别验证三种 `ErrorPolicy` 及 `PARTIAL`/`FAILED` 状态。
5. 资源释放：验证正常结束、异常结束和取消后连接均已释放。
6. 安全：连接参数从环境变量或密钥管理系统注入，不在日志输出密码。
