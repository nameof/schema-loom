# 发布验收清单

## 默认单测和依赖检查

```powershell
mvn clean test
mvn verify
mvn dependency:tree
```

运行时依赖不应包含厂商 JDBC 驱动、Spring Boot/Context 或日志实现；依赖树由发布前人工核对，不额外引入 Maven 插件。

## Java 8 编译

```powershell
$env:JAVA_HOME = "<Java 8 安装目录>"
mvn clean package
```

确认 `mvn -version` 显示 Java 8，项目源码和产物均按 Java 8 编译。

## Java 17/21 兼容运行

```powershell
$env:JAVA_HOME = "<Java 17 或 Java 21 安装目录>"
mvn clean test
mvn -DskipTests package
```

Java 17/21 运行使用 Java 8 编译产物，重点检查默认单测、驱动加载、方言测试和文件读写测试。

## MySQL 集成测试

将匹配版本的 JDBC JAR 和 descriptor 放入 `src/main/resources/drivers`，再设置：

```powershell
$env:SCHEMALOOM_IT_MYSQL_HOST = "localhost"
$env:SCHEMALOOM_IT_MYSQL_PORT = "3306"
$env:SCHEMALOOM_IT_MYSQL_DATABASE = "test"
$env:SCHEMALOOM_IT_MYSQL_USER = "<user>"
$env:SCHEMALOOM_IT_MYSQL_PASSWORD = "<password>"
# 可选；留空时自动选择驱动
$env:SCHEMALOOM_IT_MYSQL_DRIVER_ID = "mysql8"
mvn -Pintegration test
```

未配置数据库环境变量时，集成测试不会访问外部数据库。

## 性能测试

```powershell
mvn -Pperformance test
```

该 Profile 生成并处理 1,000,000 条记录，只保留当前批次，默认单测不会执行此检查。
