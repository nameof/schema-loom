# SchemaLoom

SchemaLoom 是一个独立的 Java 8 批量 ETL 库，核心依赖为 Spring JDBC、SchemaCrawler、Hutool 和 Apache POI。它不依赖 Spring Boot、Web 框架、Kettle 或厂商 JDBC 驱动。

```java
EtlResult result = EtlTask.builder()
    .source(new MemorySource(schema, records, 1000))
    .target(new MemoryTarget())
    .transformer(Transformer.identity())
    .errorPolicy(ErrorPolicy.ISOLATE_AND_CONTINUE)
    .build().run();
```

JDBC 驱动放在 `drivers` 目录，并通过 properties 描述文件加载。驱动 JAR 可执行任意代码，集成环境必须保证该目录可信。真实数据库测试使用外部环境变量，不在仓库保存凭据。

## MySQL 最小 ETL 测试

`JdbcConnectionProvider`、`JdbcTableSource` 和 `JdbcTableTarget` 可以直接组合为库到库任务：

```java
EtlResult result = EtlTask.builder()
    .source(new JdbcTableSource(
        new JdbcConnectionProvider(url, user, password),
        new QualifiedTableName(null, null, "source_table"), 1000))
    .target(new JdbcTableTarget(
        new JdbcConnectionProvider(url, user, password),
        "target_table", DatabaseType.MYSQL))
    .targetMode(TargetMode.REPLACE)
    .batchSize(1000)
    .build().run();
```

端到端测试使用以下环境变量，不配置时测试自动跳过：

```text
SCHEMALOOM_IT_MYSQL_URL
SCHEMALOOM_IT_MYSQL_USER
SCHEMALOOM_IT_MYSQL_PASSWORD
```

执行：`mvn -Dtest=JdbcEtlIntegrationTest test`。测试会创建 `schemaloom_source`，迁移到 `schemaloom_target`，再按主键顺序查询并校验行数和字段。
