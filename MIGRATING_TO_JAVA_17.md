# Java 17 migration

v0.1 以 Java 8 编译，SchemaCrawler 16.29.1 是最后一个 Java 8 构建。升级时先将编译器切换到 17，再升级 SchemaCrawler 17 和 Spring JDBC 6，随后检查所有 JDBC、POI、Hutool 依赖及模块路径警告。

SchemaLoom 不使用 Spring Context，也不依赖 `javax` Web API；升级 Spring JDBC 6 时重点确认其 Jakarta 传递依赖、异常类型和事务行为。升级后必须重新运行默认单测、方言测试和外部数据库 Profile。
