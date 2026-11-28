# 方言能力收敛设计（重构提案）

> **范围**：`8ad61b9f..3b18b2f` 之间为兼容 9 种数据库引入的判断与补丁。
> **性质**：设计提案 + 可执行验收协议，不含实现代码。
> **基线**：HEAD `3b18b2f`（main）。本文所有 `file:line` 均在该提交逐一核对。
> **约束**：项目已被外部调用，公开签名与运行时行为必须可验证地保持不变（见第 7 章）。

---

## 0 一页结论

1. **问题不在"补丁写得烂"**。这批修补大多有实机数据支撑（DB2 2013 报错、Oracle includeTables 返回 0 张表、DM 负标度崩溃），方向是对的。
2. 真正的问题是：**"数据库方言"这个概念只在「生成 SQL」这一个方向上被建模了**。`DatabaseDialect` 有 `insertSql`、`createTableSql`、`commentSql`，但没有任何一个方法回答"这个库的命名空间长什么样""未加引号的标识符怎么折叠""元数据过滤能不能下推"。
3. 于是同一个事实（"这是什么库"）在项目里有 **三套互不相认的表达**：枚举、产品名字符串、`type == DB2 || DM` 硬编码。**16 处判断分支 + 12 处重复实现 ≈ 28 处**，分布在 **14 个文件**。
4. 收敛路径：新增 `profile` 包承载方言知识 → `DatabaseDialect.profile()` 成为唯一出口 → `DatabaseMetadataService` / `JdbcDatabaseStatisticsReader` / `MetadataQuery` / `DatabaseConnectionInfo` 全部改为读 profile。
5. **公开 API 破坏面被压到最小**：`DatabaseDialect` 加 1 个方法（9 个实现类都是包级 final，外部无法自行实现）；`ConnectionProvider` 加 1 个 default 方法（现有实现零改动，且保留产品名兜底）；`DatabaseMetadataService` 的无参构造与全部现有方法签名不动。
6. 每阶段以 **"9 库快照 diff = 0 + 集成测试全绿"** 为门禁，不通过不许叠加下一阶段（第 7 章）。

---

## 1 诊断

### 1.1 同一个事实的三套表达

| 表达方式 | 载体 | 谁能用 | 典型代码 |
| --- | --- | --- | --- |
| 枚举 9 值、**无任何字段** | `driver/DatabaseType.java:3-5` | 方言注册、URL 构建 | `MYSQL, MARIADB, ORACLE, SQL_SERVER, POSTGRESQL, DB2, DM, KINGBASE, GBASE` |
| JDBC 产品名**字符串子串匹配** | `DatabaseMetadataService`、`JdbcDatabaseStatisticsReader` | 仅这两类内部 | `lower.contains("sql server") \|\| lower.contains("dm dbms")` |
| `DatabaseType` **硬编码比较** | 散落 4 个文件 | 各调用方 | `type == DatabaseType.DB2 \|\| type == DatabaseType.DM` |

### 1.2 结构性成因（这不是偷懒）

`DatabaseMetadataService` 的公开方法签名只有 `(ConnectionProvider provider, ...)`：

```java
public List<TableInfo> listTables(ConnectionProvider provider, MetadataQuery query)   // :71
public TableInfo getTable(ConnectionProvider provider, QualifiedTableName name)      // :85
```

而 `ConnectionProvider`（`driver/ConnectionProvider.java:10-20`）只有 `getConnection()` 和 `close()`。
**它结构上拿不到 `DatabaseType`**，所以只能回头去问驱动的 `getDatabaseProductName()`。
项目里每一处 `if (product.contains(...))` 都是这个约束的产物——**补丁打在了正确的位置，只是那个位置本身缺一个抽象**。

### 1.3 量化

- 方言判断分支：**16 处**（见 3.1）
- 完全重复的实现：**12 处**（见 3.2）
- 涉及文件：**14 个**（5 个服务/模型类 + 9 个方言）

### 1.4 能力模型必须覆盖的 11 个维度（本表即 `DatabaseProfile` 的输入）

| # | 维度 | 各库事实 |
| --- | --- | --- |
| 1 | 命名空间层级 | MySQL 家族：库即 schema（库名放 catalog）；Oracle：只有 schema（database 是服务名，不能当 catalog 下推）；SQL Server / DB2 / DM：库.模式.对象；PG / Kingbase：database + schema |
| 2 | 默认 schema | PG / Kingbase = `public`；SQL Server = `dbo`；DB2 / DM 由会话决定（**不下推**） |
| 3 | 空模式段 `库..表` | 只有 SQL Server 接受 |
| 4 | 未加引号标识符大小写 | DB2 / DM → 大写；Oracle / PG 折叠（大写/小写）；SQL Server 保留 |
| 5 | 引用字符 | MySQL 家族反引号，其余双引号 |
| 6 | 字符串转义 | MySQL 家族反斜杠是转义符，其余不是 |
| 7 | schema 下推 | 仅 SQL Server / DM / DB2 可用（Oracle 一旦启用 `includeSchemas` 返回 0 张表） |
| 8 | 表名下推 | Oracle 不可用（插件把正则当表名模式查系统表）——**与 #7 是相反的白/黑名单策略** |
| 9 | SchemaCrawler 兼容插件 | PG → `postgresql`；DB2 → `db2`；Kingbase → `postgresql` |
| 10 | 生成列来源 | MySQL 家族 / PG / Kingbase / SQL Server / DB2 各一套系统表查询；Oracle / DM 不查 |
| 11 | 统计查询族 | MySQL 家族（GBASE 复用）/ Oracle / SQL Server / PG+Kingbase / DB2 / DM 共 6 族 |

---

## 2 五条主线

### 主线 A：`DatabaseProfile` 值对象 —— 让"问不出类型"这件事消失

#### A.1 包结构（新增，不依赖 driver/metadata/dialect，避免循环依赖）

```java
package io.github.nameof.schemaloom.profile;

/** 方言能力档案：把散在各处的"这个库是什么库"收成一组只读事实。 */
public final class DatabaseProfile {
    /** 按数据库类型取档案；实现类在包内，外部只拿值。 */
    public static DatabaseProfile of(DatabaseType type) { ... }
    /** 产品名兜底：仅在 provider 未声明类型时使用（外部自定义 provider 的兼容路径）。 */
    public static DatabaseProfile resolve(String productName) { ... }

    public NamespaceModel        namespaceModel();      // 枚举 4 值
    public SchemaDefaults        schemaDefaults();      // 三个语义不同的默认值
    public IdentifierRules       identifiers();         // 大小写折叠 + 引用字符 + 反斜杠转义
    public QualifiedNameRules    qualifiedNames();      // 是否接受空模式段
    public PushdownRules         pushdown();            // schema/表名下推 + 兼容插件
    public StatisticsFamily      statistics();          // 6 族
    public GeneratedColumnSource generatedColumns();    // 5 种来源 + NONE
}
```

```java
public enum NamespaceModel {
    CATALOG_ONLY,    // 库即 schema：库名进 catalog（MySQL / MariaDB / GBase 8a）
    SCHEMA_ONLY,     // 只有 schema，database 不是 catalog（Oracle）
    CATALOG_SCHEMA,  // database + schema（PostgreSQL / Kingbase / DB2 / DM）
    THREE_LEVEL      // 库.模式.对象，且接受空模式段（SQL Server）
}
```

> 命名空间层级与"是否接受空模式段"是**两个独立维度**：DB2 / DM 同样是"库.模式.对象"，但不接受 `库..表`，因此归 `CATALOG_SCHEMA`；空模式段由 `QualifiedNameRules` 单独表达，不塞进枚举。

```java
/**
 * 默认 schema 必须拆成三种语义，合成一个字段必然出错。
 * 反例：SQL Server 若把 dbo 当 connectionDefault，未配置 schema 时会被下推成 dbo，
 * 结果只看见 dbo 一个 schema，其余 schema 的表全部"消失"。
 */
public final class SchemaDefaults {
    private final String connectionDefault;    // 连接级默认（PG / Kingbase = public）
    private final String ddlFallback;          // DDL 限定名兜底（SQL Server = dbo）
    private final String statisticsFallback;   // 统计查询兜底（PG = public）
}
```

#### A.2 方言接口唯一新增

```java
public interface DatabaseDialect {
    // ... 现有 15 个方法（13 abstract + 2 default）全部不动 ...

    /** 本方言的能力档案：命名空间、标识符规则、下推策略、统计族、生成列来源。 */
    DatabaseProfile profile();
}
```

**决策 1：不在接口上加任何 `boolean supportsXxx()`。**
每个 boolean 都会与 profile 里的字段形成**第二个真值来源**，迟早漂移。要么问 profile，要么什么都没有。

**决策 2：`supportsEmptySchemaSegment` 不从 `protected` 提到接口。**
它今天只有 `AbstractDialect.quote()`（`AbstractDialect.java:37/45`）在用，metadata 根本不需要这个信号。
提接口只是让 metadata 为了问一个 boolean 先去拿到方言实例——**绕不开"拿不到 DatabaseType"这个根问题**。
正确落点：`AbstractDialect.quote()` 改读 `profile().qualifiedNames().allowsEmptySchemaSegment()`，`SqlServerDialect.java:25` 的 override 删除。

#### A.3 真正的解锁点：`ConnectionProvider.databaseType()`

```java
public interface ConnectionProvider extends AutoCloseable {
    Connection getConnection();
    void close();

    /**
     * 本连接对应的数据库类型；无法确定时返回 null。
     * 默认方法，现有匿名实现无需改动；返回 null 时元数据服务退回产品名解析。
     */
    default DatabaseType databaseType() { return null; }
}
```

内置实现 `JdbcDriverLoader.Provider`（`JdbcDriverLoader.java:266-287`）构造时已持有 `Entry`，补一个 `DatabaseType` 字段即可（`JdbcDriverLoader.connect(DatabaseConnectionInfo)` 手上就有）。

`DatabaseMetadataService` 保持 **无实例字段 + `new` 无参**（这是文档承诺的用法），在方法入口解析一次：

```java
private DatabaseProfile profile(ConnectionProvider provider) {
    DatabaseType type = provider.databaseType();
    return type != null ? DatabaseProfile.of(type) : DatabaseProfile.resolve(productName(provider));
}
```

`DatabaseProfile.resolve(String)` 是**全项目唯一保留的产品名字符串匹配点**，且只服务外部自定义 provider 的兜底。内置 provider 走枚举，零字符串比较。

#### A.4 消除清单

| 位置 | 现在 | 改为 |
| --- | --- | --- |
| `DatabaseMetadataService.java:179-184` | `pushesSchemaFilter`：`sql server` / `dm dbms` / `db2` 白名单 | `profile.pushdown().schemaFilter()` |
| `:211-215` | `pushesTableFilter`：排除 `oracle` 黑名单 | `profile.pushdown().tableFilter()` |
| `:218-236` | 插件兼容：`postgresql`/`db2`/`kingbase` + 名字映射 | `profile.pushdown().schemaCrawlerPlugin()` |
| `:336-345` | 生成列 5 个 boolean | `profile.generatedColumns()` |
| `:377-382` | 生成列 namespace 参数三选一 | `profile.namespaceModel()` 决定取 schema 还是 catalog |
| `JdbcDatabaseStatisticsReader.java:28/36-42/47-53` | 构造取产品名 + 两组 6 分支 | `profile.statistics()` 决定查询族 |
| `MetadataQuery.java:28-41` | 按 `DatabaseType` 四段分支 | `profile.namespaceModel()` + `profile.schemaDefaults()` |
| `DatabaseConnectionInfo.java:37` | PG/KB 硬编码 `public` | `DatabaseProfile.of(type).schemaDefaults().connectionDefault()` |
| `DatabaseConnectionInfo.java:79-81` | `DB2 \|\| DM` 大写 | `profile.identifiers().unquotedCase()` |

---

### 主线 B：`Namespace` 值对象 —— 停止到处传两个可空 String

现状：`MetadataQuery` 三可空字段（`MetadataQuery.java:7`，构造器 `:9-13`），`QualifiedTableName(catalog, schema, table)`（24 行，只有 table 非空校验）。

```java
/** 命名空间二元组；两段都可空，null 表示"未指定"。 */
public final class Namespace {
    public static Namespace of(String catalog, String schema) { ... }
    public String catalog();
    public String schema();
    /** 本命名空间显式给出时覆盖 base，未给出则沿用 base —— "表名自带命名空间优先"只写一次。 */
    public Namespace overrideWith(Namespace base) { ... }
    // equals / hashCode / toString
}
```

- `QualifiedTableName` / `MetadataQuery` **组合而非合并**：两者语义不同（一个标识对象、一个标识查询范围），且测试里 `new QualifiedTableName(` / `new MetadataQuery(` / `info.table(` 合计 **91 处**引用（生产 10 处）。合并会把这 91 处一次性推上手术台，与"外部调用需要行为稳定"的目标直接冲突。
- 两者都**保留现有构造器**，新增 `Namespace` 重载，并补 `equals/hashCode/toString`。
- `MetadataQuery.fromConnectionInfo:53-57` 的手工三段合并 → `scope.overrideWith(table.namespace())`。

**SchemaCrawler 的 schema 全名分隔符（`.` vs `/`）不进领域模型。** 那是插件细节，收敛到拆分类（`SchemaCrawlerCatalogLoader`）一处常量即可，进 profile 会让领域模型替第三方库背 bug。

---

### 主线 C：权威 Schema 从源头注入 —— 删掉 `align` 与公有 `coerce`

#### C.1 现状为什么是补丁

`JdbcTableSource` 用自己算出的表元数据 Schema 当"权威"，但读取实际由 `JdbcQuerySource` 完成（`JdbcTableSource.java:105-106`），后者用**驱动的 ResultSetMetaData** 建 Schema。两套 Schema 冲突时靠 `align` 逐字段强转（`:201-209`）：

```java
private DataRecord align(RecordSchema schema, DataRecord record) {
    for (int i = 0; i < fields.size(); i++)
        values.add(i < record.getValues().size()
                ? LogicalTypeCatalog.coerce(record.getValues().get(i), fields.get(i).getLogicalType())
                : null);   // ← 按位置对齐：列序不一致就静默错列
    return new DataRecord(schema, values);
}
```

代价：**每行 3 次 `DataRecord` 构造**（驱动记录 → align → 引擎），每次构造都含一次 list 复制 + 一遍全字段类型校验。

#### C.2 目标形态

```java
// JdbcQuerySource：新增包级构造器，接受权威 Schema
JdbcQuerySource(ConnectionProvider provider, String sql, List<Object> params,
                int fetchSize, LargeFieldPolicy policy, RecordSchema authoritative)
```

- 注入时 `recordSchema()` **完全跳过** `inspect query`（现状为探测 Schema 会把整条 SQL 真跑一遍；D1 已给探测查询加 `maxRows=1` 限制回传行数，但服务端仍执行完整查询计划，属缓解非根治。详见 `ISSUE_TRACKER.md` 的 D1 条目与第五节实测）。
- 读取时按 `authoritative.getFields()` 的逻辑类型取值；列绑定用 **label → 列号映射 + 数量校验**，不再依赖位置。
- `JdbcTableSource` 拆 `ensureProvider` / `ensureDelegate` / `ensureSchema` 三步，解开
  `ensureSchema → ensureTableInfo → ensureProvider → 需要 schema` 的循环。

#### C.3 必须先做列绑定，再删 align

**顺序不能反。** 只需把 `align` 删掉、把权威 Schema 塞进去，遇到 `SELECT *` 列序与表元数据列序不同（INVISIBLE 列、视图、驱动重排）就会**静默写错列**——这是本轮重构唯一可能损坏数据的点。

绑定规则（包级构造器入参 `Map<String,Integer> labelIndex` 或直接内建解析）：

1. 用 `ResultSetMetaData.getColumnLabel(i)` 建 `label → index` 映射（大小写不敏感）
2. 权威 Schema 的每个字段按名查 index；**查不到直接抛 `SchemaLoomException`**，不静默填 null
3. 映射数量与字段数量不等 → 抛异常（附两侧列名快照）

删除项：`JdbcTableSource.align:201-209` 及两处调用、`LogicalTypeCatalog.coerce(Object, LogicalType)` 公有重载。
**保留**私有 `coerce(Object, Class)`——它服务 `nullable()` 的驱动兜底，与 align 无关。

#### C.4 障碍（写在这里，不留给实施者踩）

- 大字段判据仍绑驱动元数据（CLOB/BLOB 走 `LargeFieldPolicy`），注入 Schema 后这条路径**不变**，不要顺手统一
- 视图 / 非同表查询列集合可能多于表元数据（表达式列）→ 数量校验只对 `JdbcTableSource` 的 `SELECT *` 生效，`JdbcQuerySource` 的通用构造器保持"无权威 Schema"行为
- 数值方向：驱动侧 BigDecimal ↔ 表元数据 Float64 的收敛仍走 `LogicalTypeCatalog`，只是从"每行每字段"提前到"绑定期一次"

---

### 主线 D：类型映射补「绑定」维度

`DatabaseTypeMapping` 现在只有 `supported` + `ddlTypeResolver` + `degradesTo`（`DatabaseTypeMapping.java:15-23`），**无法表达**"DDL 用 `NUMBER(1)`、绑定时 `setNull` 要用 `NUMERIC`"这种分裂。

```java
/** 绑定侧能力；与 DDL 侧分开，因为同一个列在两侧的正确取值可能不同。 */
public final class Binding {
    private final LogicalType valueType;  // 写入时按哪个逻辑类型编码；null = 同原类型
    private final Integer    nullSqlType; // setNull 用哪个 java.sql.Types；null = 由逻辑类型推导
    private final boolean    lossy;       // 是否丢失信息（决定是否告警）
    public static Binding of(LogicalType valueType, Integer nullSqlType, boolean lossy);
    public static Binding lossy(LogicalType valueType);   // 等价于今天的 degraded()
    public static Binding nullType(int sqlType);          // 只换 null 类型码
}
```

- `DatabaseTypeMapping.degraded(ddlType, to)` **保留为 `bound(ddlType, Binding.lossy(to))` 的语法糖**，9 个方言现有写法一行不改。
- `degradesTo()` 只在 `lossy` 时返回非空 → `validateCapabilities`（`JdbcTableTarget.java:191`）的告警不会因为"无损绑定"误报。
- `JdbcTableTarget.nullSqlType:303-307` → `mapping.nullSqlType(field)`；`JdbcValueCodec.degradeValue` 改名 `adapt`，语义从"降级"变成"按绑定编码"。

**硬约束**：只允许三种形态——换 null 类型码、无损换值类型、现有两种时区丢失。**不引入任意 `BiFunction`**，否则会演化成第二套类型系统，比现在的补丁更难维护。

---

### 主线 E：复制粘贴收口

| 重复 | 位置 | 收口 |
| --- | --- | --- |
| `commentLiteral` 三份**逐字符相同** | `MySqlDialect.java:48-50`、`MariaDbDialect.java:49-51`、`GBase8aDialect.java:75-77` | `AbstractDialect.stringLiteral(String)` 唯一实现，按 `profile.identifiers().escapesBackslash()` 决定 |
| `literal` 一份（不转义反斜杠） | `SqlServerDialect.java:135-137` | 同上，反斜杠开关为 false |
| 引号感知的"取最后一段" 三份**逐字符相同** | `PostgreSqlDialect.java:30-41`、`KingbaseDialect.java:31-42`、`Db2Dialect.java:41-52` | `AbstractDialect.lastIdentifier(String)` |
| 非引号感知的"取最后一段" | `DmDialect.java:34-38` | 同上，顺带修掉"引号内含点号"被切错 |
| 手写保空段分割 + 去引号 | `SqlServerDialect.java:64-91`（+ `:128-133`） | `AbstractDialect.segments(String)`（引号感知），`SqlServerDialect` 只留语义差异 |
| 486 行元数据服务 | `DatabaseMetadataService.java`（全文件） | 门面 ~110 行 + 4 个包级类（见下） |
| 12 个统计私有方法 | `JdbcDatabaseStatisticsReader.java:56-240` | 按族拆 6 个查询类 |

`stringLiteral` 的**转义顺序是坑**：必须先转反斜杠再转单引号。反了会让 `\'` 变成 `\''`，MySQL 家族解析出多余的引号。现有三份实现顺序是对的，收口时照抄，不要"顺手整理"。

元数据服务拆分：

| 新类（均为包级 final） | 职责 | 迁出内容 |
| --- | --- | --- |
| `SchemaCrawlerCatalogLoader` | 建 Catalog、兼容插件、全局锁、ConnectionSource 适配 | `:118-151`、`:218-236`、`:271-287` |
| `MetadataPatterns` | schema 全名 / 表名 LIKE→正则 | `:153-178`、`:245-270`、`:288` |
| `SchemaCrawlerTableMapper` | Catalog → `TableInfo`（列、主键、索引、外键、约束） | `:290-335`、`:412-486` |
| `GeneratedColumnProbe` | 生成列旁路查询 | `:336-411` |
| `DatabaseMetadataService`（门面） | 9 个公开方法 + 委托 + 统计 | 保持签名 |

**统计查询不下沉到方言**：`dialect` 包会反向依赖 Spring JDBC 与统计 DTO，层次倒置。族信息进 profile，查询实现在 metadata 包内按族分文件。

---

## 3 补丁消灭映射表

### 3.1 判断分支（16 处）

| # | 位置 | 内容 | 主线 | 替代 | 行为变更 |
| --- | --- | --- | --- | --- | --- |
| 1 | `DatabaseMetadataService.java:183` | schema 下推白名单 | A | `pushdown().schemaFilter()` | 否 |
| 2 | `:214` | 表名下推黑名单（Oracle） | A | `pushdown().tableFilter()` | 否 |
| 3 | `:222` | 插件兼容白名单 | A | `pushdown().schemaCrawlerPlugin()` | 否 |
| 4 | `:225` | 插件名映射 | A | 同上 | 否 |
| 5 | `:340-344` | 生成列 5 个 boolean | A | `generatedColumns()` | 否 |
| 6 | `:380-382` | 生成列 namespace 参数 | A/B | `namespaceModel()` | 否 |
| 7 | `:189` | `productName()` 取产品名 | A | 仅作 `resolve()` 兜底 | 否 |
| 8 | `JdbcDatabaseStatisticsReader.java:36-41` | 表统计 6 分支 | A | `statistics()` | 否 |
| 9 | `:47-52` | schema 统计 6 分支 | A | 同上 | 否 |
| 10 | `:28` | 构造时取产品名 | A | 同上 | 否 |
| 11 | `MetadataQuery.java:28-29` | MySQL 家族 → catalog | A/B | `namespaceModel()` | 否 |
| 12 | `:30-34` | PG：不下推 catalog + public | A/B | `namespaceModel()` + `connectionDefault` | 否 |
| 13 | `:37-38` | Kingbase：catalog 恒 null + public | A/B | 同上 | 否 |
| 14 | `DatabaseConnectionInfo.java:37` | PG/KB 写死 `public` | A | `connectionDefault()` | 否（物化值不变） |
| 15 | `DatabaseConnectionInfo.java:79-81` | `DB2 \|\| DM` 大写 | A | `identifiers().unquotedCase()` | 否 |
| 16 | `JdbcViewMigrator.java:107` | Oracle 命名空间比较特例 | A | `namespaceModel() == SCHEMA_ONLY` | 否 |

### 3.2 重复实现（12 处）

| # | 位置 | 重复份数 | 替代 | 删除行数（估） |
| --- | --- | --- | --- | --- |
| 1 | `MySqlDialect:48-50` / `MariaDbDialect:49-51` / `GBase8aDialect:75-77` | 3 | `stringLiteral` | 9 |
| 2 | `SqlServerDialect:135-137` | 1 | 同上 | 3 |
| 3 | `PostgreSqlDialect:30-41` / `KingbaseDialect:31-42` / `Db2Dialect:41-52` | 3 | `lastIdentifier` | 33 |
| 4 | `DmDialect:34-38` | 1 | 同上 | 5 |
| 5 | `SqlServerDialect:64-91`、`:128-133` | 1 | `segments` | 28 |
| 6 | 视图名 `toUpperCase`：`Db2Dialect:62,65` / `DmDialect:56` / `OracleDialect:43` | 3 | `identifiers().unquotedCase()` | 4 |
| — | 合计 | — | — | **≈ 82 行** |

### 3.3 唯一的行为变更（必须单独提交）

**去掉视图名的无条件大写**（`Db2Dialect:62,65`、`DmDialect:56`、`OracleDialect:43`）。

- 现状：无论对象是否加引号创建，查询 `ALL_VIEWS`/`SYSCAT.VIEWS` 时视图名一律 `toUpperCase` → 用引号建的小写视图名**永远查不到**，视图迁移静默失败
- 改为：走 `identifiers().unquotedCase()`，仅"未加引号"语义下折叠
- 判定为**行为修正**而非重构，因为它会改变"加引号小写视图"的结果。第 7 章白名单里单列，并要求补一条集成用例
- **2026-11-28 已由 B15 增量修复先行落地**（ISSUE_TRACKER B15 归档）：三个方言的 `viewDefinitionQuery` 去掉 `toUpperCase`、`uppercasesIdentifiers()` 补入 Oracle，未走 profile 化路径。本节剩下的仅是「实现方式迁到 profile」的等价重构，不再有行为变更；P2 描述中的「视图名去大写」部分视为已完成

---

## 4 分阶段迁移

```
P0  新增 profile 包 + DatabaseProfile.of(type) 静态工厂（零行为变化，删文件即回滚）
 │
 ├─► P1  方言接入 profile：stringLiteral 收口、空模式段下沉（行为等价）
 │    ├─► P1b 限定名解析合并（纯重构；顺带修 SQL Server 引号内点号）
 │    ├─► P2  输入规范化改由 profile 驱动；视图名去大写【唯一行为变更，单独提交】
 │    ├─► P3  Namespace + MetadataQuery（MetadataQueryTest 14 用例是安全网）
 │    ├─► P4a 元数据服务纯拆分（零行为变化）
 │    │    └─► P4b 下推/兼容策略改由 profile 驱动 + ConnectionProvider.databaseType()
 │    ├─► P5  统计查询拆族（按族提交，6 次）
 │    └─► P6  Binding 维度（前置：先跑探测确认 Oracle setNull(Types.BOOLEAN) 是否真失败）
 │
 └─► P7  收尾：JdbcViewMigrator 分支改 profile、删死代码、同步 README
```

每阶段的硬要求：**可编译、可单独提交、可单独回滚**，且过第 7 章对应门禁。

依赖说明：

- `P1` 依赖 `P0`（profile 存在），不依赖 `P3`
- `P4b` 依赖 `P0` 与 `P3`（profile 要在方法入口解析，`MetadataQuery` 要能表达命名空间）
- `P6` 与其它阶段正交，但**必须等 P-0 基线快照**，否则无法判定类型映射矩阵是否变化
- `P2` 必须在 `P1b` 之后（先有统一的"未加引号"解析，再谈折叠）

---

## 5 需要拍板的 6 个风险点

| # | 风险 | 我的倾向 | 需要你确认的点 |
| --- | --- | --- | --- |
| 1 | `Namespace` 是否值得公开 | 公开，但只作为 `QualifiedTableName`/`MetadataQuery` 的**内部组合**，不新增公开 getter 之外的用法 | 外部调用方是否在用 `getCatalog()/getSchema()` 拼字符串？新增类是否算 API 面扩大 |
| 2 | `DatabaseType` 持有 profile 的耦合 | **不持有**，用 `DatabaseProfile.of(type)` 静态工厂 | 将来 Oracle 12c vs 23ai 差异会装不进一个枚举常量；静态工厂可按 `type + 运行时产品版本`细分，枚举保持纯标识 |
| 3 | Oracle BOOLEAN 缺口是否真在 `nullSqlType` | 不确定，**先探测再改** | `setBoolean` 与 `getObject(Boolean.class)` 各有问题，可能真正答案是"DDL 用 NUMBER(1)、值是 0/1 的 Integer" |
| 4 | 删 `DatabaseConnectionInfo:37` | **保留物化**，只把规则来源搬到 profile | 删掉会让 `getSchema()` 对未配置 PG 连接返回 null，破坏公开语义 |
| 5 | 视图名去大写 | 做，但单独提交 + 单独用例 | 属行为变更，外部若依赖"大写视图名"会感知到 |
| 6 | `MetadataQuery` 加 `equals/hashCode` | 加 `toString` 即可，`equals/hashCode` 看是否需要缓存键 | 当前没有以 MetadataQuery 为键的缓存 |

---

## 6 与 `doc/ISSUE_TRACKER.md` 的对应

**顺带解决**：

| 台账条目 | 被哪条主线消掉 |
| --- | --- |
| E1 五层套娃 | A（profile 收敛）+ E（服务拆分） |
| E3 两套命名空间语义 | B（`MetadataQuery.fromConnectionInfo` 唯一入口） |
| E6 两套 LIKE→正则 | E（`MetadataPatterns` 单点） |
| F1 catalog 为空不下推 | A.3（profile 决定，不再靠调用方传值） |
| F3 README 与代码不符 | P7 收尾同步 |

**不解决（明确划出边界）**：

- **B11** Oracle `includeTables` 失效——根因在第三方插件，本方案只把它**固化为能力位**（`tableFilter=false`），不尝试修复
- **B12** SQL Server 连 `master` 只枚举出部分 `sl_*` 表——**基线即复现**，与本轮改动无关，见 7.4 陷阱 1
- **D1** SchemaCrawler 探测跑全表——C 只消除 `inspect query` 那一次，SchemaCrawler 内部行为不动

---

## 7 可执行验收协议（Podman 实测）

> 前提：项目已被外部调用。**"能编译能过单测"不算通过**，必须在 9 种真实数据库上证明行为一致。
> 本章的每条命令都应能原样粘贴执行。执行环境已核实（2026-11-11）。

### 7.1 环境矩阵

| 方言 | 端点 | 库 / 模式 | 账号 | driverId | 就绪状态 |
| --- | --- | --- | --- | --- | --- |
| MYSQL | `localhost:3306` | `dbmask` → **改用 `schemaloom_it`** | root / `root` | mysql8 | 外部实例，可用 |
| MARIADB | `127.0.0.1:13306` | `schemaloom_it` | root / `Root_IT_2026` | mariadb114 | 容器 `schema-loom-mariadb-it` Up |
| GBASE | `127.0.0.1:15258` | `schemaloom_it` | schemaloom / `GBase_IT_2026` | gbase8a | 容器 `schema-loom-gbase-it` Up |
| ORACLE | `127.0.0.1:1521` | `FREEPDB1` / `SL_SOURCE` | sl_source / `Sl_IT_2026` | oracle23 | 容器 `schema-loom-oracle-it` Up |
| SQLSERVER | `127.0.0.1:11433` | `master`（测试自建/清理 `dbo.schemaloom_*`） | sa / `SchemaLoom_IT_2026!` | sqlserver2022 | 容器 Up |
| POSTGRESQL | `127.0.0.1:15432` | `sl_target` | schemaloom / `SchemaLoom_IT_2026!` | postgresql16 | 容器 Up |
| DB2 | `127.0.0.1:15000` | `BLUDB` | db2inst1 / `db2inst1` | db2 | 容器 Up |
| DM | `127.0.0.1:15236` | `SYSDBA` / `SL_TARGET` | SYSDBA / `Sysdba001` | dm | 容器 Up |
| KINGBASE | `127.0.0.1:54321` | `kingbase` | system / 启动时自定 | kingbase8 | **待启动**；镜像 `localhost/kingbase:V009R001C010` 已在本地，无需联网 |

**自检（30 秒，任一不满足就别跑，否则会把环境问题记成代码回归）**

```bash
podman ps --format '{{.Names}} {{.Status}}' | grep schema-loom-
```

期望看到 7 个 `Up`：`schema-loom-db2-it` / `-dm-it` / `-mariadb-it` / `-oracle-it` / `-gbase-it` / `-sqlserver-it` / `-postgres-it`。

**补齐 `.workbuddy/it-env.sh`**（现有文件只覆盖 Oracle/PG/DM/SQLServer/DB2，缺 4 组）

```bash
# ---- 追加：MariaDB ----
export SCHEMALOOM_IT_MARIADB_HOST=localhost
export SCHEMALOOM_IT_MARIADB_PORT=13306
export SCHEMALOOM_IT_MARIADB_DATABASE=schemaloom_it
export SCHEMALOOM_IT_MARIADB_USER=root
export SCHEMALOOM_IT_MARIADB_PASSWORD='Root_IT_2026'

# ---- 追加：GBase 8a ----
export SCHEMALOOM_IT_GBASE_HOST=localhost
export SCHEMALOOM_IT_GBASE_PORT=15258
export SCHEMALOOM_IT_GBASE_DATABASE=schemaloom_it
export SCHEMALOOM_IT_GBASE_USER=schemaloom
export SCHEMALOOM_IT_GBASE_PASSWORD='GBase_IT_2026'

# ---- 追加：MySQL（外部实例）----
export SCHEMALOOM_IT_MYSQL_HOST=localhost
export SCHEMALOOM_IT_MYSQL_PORT=3306
export SCHEMALOOM_IT_MYSQL_DATABASE=schemaloom_it
export SCHEMALOOM_IT_MYSQL_USER=root
export SCHEMALOOM_IT_MYSQL_PASSWORD='root'
export SCHEMALOOM_IT_MYSQL_DRIVER_ID=mysql8

# ---- 追加：Kingbase ----
export SCHEMALOOM_IT_KINGBASE_HOST=localhost
export SCHEMALOOM_IT_KINGBASE_PORT=54321
export SCHEMALOOM_IT_KINGBASE_DATABASE=kingbase
export SCHEMALOOM_IT_KINGBASE_USER=system
export SCHEMALOOM_IT_KINGBASE_PASSWORD='<启动容器时定的值>'
export SCHEMALOOM_IT_KINGBASE_DRIVER_ID=kingbase8
```

**MySQL 单独建库**（不要指向业务库 `dbmask`：集成测试会 `DROP/CREATE schemaloom_source|target|boundary`）

```sql
CREATE DATABASE IF NOT EXISTS schemaloom_it DEFAULT CHARACTER SET utf8mb4;
```

**启动 Kingbase**

```powershell
$env:SCHEMALOOM_IT_KINGBASE_PASSWORD='<自定，需与上面一致>'; scripts/start-kingbase-integration.ps1
```

**加载环境并跑一个最小烟测**

```bash
set -a; . .workbuddy/it-env.sh; set +a
mvn -o -Pintegration test -Dtest=MariaDbIntegrationTest
```

### 7.2 基线快照机制（判据的基础设施）

**先录基线，再动代码。** 否则"行为一致"只能靠肉眼比对测试断言，覆盖不到 `listTables` 过滤边界这类路径。

新增 `src/test/java/io/github/nameof/schemaloom/integration/BehaviorSnapshotIntegrationTest.java`（P-0 落地，**本次不写**）。职责：对每个方言把**公开 API 的可观测输出**序列化为规范化 JSON，路径 `.workbuddy/baseline/<DIALECT>@<commit>.json`（已随 `.workbuddy/` 被 gitignore）。

采集面（全部走 public API，不碰私有实现）：

1. `DatabaseInfo`：`productName` / `productVersion` / `driverName`
2. `listTables` 的 `(catalog, schema, table)` 排序集合——**三种 query 形态各采一次**：`fromConnectionInfo(info, table)`、显式 schema、表名模式下推
3. `getTable` 全字段：columns（name / jdbcType / logicalType / precision / scale / length / nullable / autoIncremented / generated / generatedExpression / remarks / defaultValue）、primaryKey、indexes、foreignKeys、constraints、表 remarks、`QualifiedTableName` 三段
4. `getTableStatistics` / `getSchemaStatistics` 的计数字段（**不含耗时**）
5. `getViewDefinition`（固定夹具视图）
6. 方言 DDL 产物：`createTableSql` / `commentSql` / `indexSql` / `insertSql` / `identityInsertOnSql` 对同一夹具 Schema 的输出
7. 类型映射矩阵：`LogicalType` 全枚举 → `DatabaseTypeMapping`（`isSupported` / `getDdlType` / `degradesTo`）
8. 限定名语义：`quote(QualifiedTableName)` 三种形态（带 schema / 无 schema / 空 schema 段）

归一化规则（**不归一化就必然误报**）：

- 数组一律排序；剔除版本号、时间戳、对象 OID、统计耗时
- `\r\n` → `\n`；数值统一 `BigDecimal.toPlainString()`
- 不同库的产品版本号必然不同 → 只比同库跨提交，不做跨库比对

判定：

```bash
set -a; . .workbuddy/it-env.sh; set +a
mvn -o -Pintegration test -Dtest=BehaviorSnapshotIntegrationTest
diff -u .workbuddy/baseline/ORACLE@3b18b2f.json .workbuddy/baseline/ORACLE@<phase-commit>.json
```

要求为**空 diff**；白名单差异必须在 3.3 登记过（当前只有"视图名去大写"一项）。

### 7.3 分阶段门禁矩阵

| 阶段 | 必跑 | 通过判据 |
| --- | --- | --- |
| **P-0** 录基线 | `-Dtest=BehaviorSnapshotIntegrationTest` | 9 份 JSON 生成；与已知缺陷（B12）一致不算失败，不一致则先排查环境 |
| **P0** profile 包纯新增 | 快照 + `mvn -o -Pintegration test` | 9 库快照 diff = 0；全部集成测试绿 |
| **P1** stringLiteral / 空模式段收口 | 快照 + `-Dtest=MariaDbIntegrationTest+GbaseIntegrationTest+SqlServerEtlIntegrationTest+DialectBehaviorProbeIntegrationTest` | diff = 0（行为等价） |
| **P1b** 限定名解析合并 | 快照 + `-Dtest=Db2IntegrationTest+PostgreSqlIntegrationTest+KingbaseIntegrationTest+SqlServerEtlIntegrationTest` | diff = 0；新增单测「引号内含点号」通过 |
| **P2** 规范化改 profile + 视图名去大写 | 快照 + `-Dtest=Db2IntegrationTest+DmIntegrationTest+OracleEtlIntegrationTest` | 仅 3.3 白名单内的 diff；其余 = 0 |
| **P3** Namespace + MetadataQuery | 快照 + `-Dtest=MetadataQueryTest` + 全量集成测试 | diff = 0；逐库比对 `getCatalog()/getSchema()` 返回值 |
| **P4a** 元数据服务纯拆分 | 快照 + 全量集成测试 | diff = 0（零行为变化） |
| **P4b** 下推策略改 profile + `databaseType()` | 快照（重点看 `listTables` 集合与 `getSchemaStatistics`） | diff = 0；Oracle 仍不下推表名、PG 仍不把 database 下推为 catalog |
| **P5** 统计查询拆族 | 快照（统计项）+ 全量集成测试 | 统计数值 diff = 0 |
| **P6** Binding 维度 | 快照（类型映射矩阵）+ `-Dtest=TypeBindingProbeIntegrationTest+OracleVendorTypeProbeIntegrationTest` | 仅白名单 diff；Oracle BOOLEAN 绑定行为有实测证据 |
| **P7** 收尾 | 快照 + 全量集成测试 | diff = 0 |

全量命令（9 库一次跑完，环境变量齐备时）：

```bash
set -a; . .workbuddy/it-env.sh; set +a
mvn -o -Pintegration test
```

每阶段记录落 `.workbuddy/verification/<phase>-<date>.md`：命令原文、耗时、diff 结果、失败项根因。**最终结论单独一次提交**回写 `doc/ISSUE_TRACKER.md`。

### 7.4 已知环境陷阱（不写下来就一定误判）

1. **B12 基线即复现**：SQL Server 连 `master` 只枚举出部分 `sl_*` 表，`git stash` 对比确认改动前同样如此。→ 判据必须是「与基线 diff」，不能是「绝对断言期望 N 张表」。
2. **探针类测试硬编码端口、无 Assume 守卫**：`DialectBehaviorProbeIntegrationTest`（DB2 15000 / DM 15236 / Oracle 1521 / GBase 15258）、`TypeBindingProbeIntegrationTest`、`OracleVendorTypeProbeIntegrationTest`、`Db2SchemaPushdownProbeIntegrationTest` 直连固定端口**且会真实建表**。改端口要同步改测试；跑之前确认容器在 Up。
3. **Oracle 需要预置 schema**：`SL_SOURCE` / `SCHEMALOOM` 与探针表被清理后，探针必红——先确认再跑。
4. **GBase 口令不能含单引号**（启动脚本显式拒绝）；容器重启后 IP 漂移会加载到历史实例目录，表现为"能连上但库不见了"。
5. **DB2 容器冷启动慢**（首次可达数分钟）→ 以 `podman ps` 的 `Up` 为准，不要盲目重跑。
6. **必须离线构建**：`mvn -o`，驱动来自 `drivers/*.jar`；`*IntegrationTest` 被 surefire 默认排除（`pom.xml:98-101`），只有 `-Pintegration` 才跑（`pom.xml:106-124`）。
7. **`dbmask` 是业务库**：见 7.1 的建库 SQL，务必指向 `schemaloom_it`。
8. **Kingbase 驱动默认 `autoCommit=false`**，`JdbcDriverLoader` 的代理设置不生效，测试里已用原生连接绕开——验收时不要"顺手统一"。

### 7.5 失败与回滚判据

- 任一库出现**非白名单 diff** 或任一集成测试红 → 该阶段失败：`git revert <phase-commit>`，重跑上一阶段门禁确认回到绿，**不允许带病叠加**。
- 同一 diff 在 P-0 基线也出现 → 归为环境/既有缺陷，记入附录，不阻塞。
- 每个阶段结束在附录追加一行：阶段 / 提交号 / 9 库结果 / 快照哈希 / 结论。

---

## 8 明确不做

- 本文件不含实现代码，只给接口/类签名草案与结构
- 不在本文件里评估"某个库的某个 BUG 怎么修"——那是 `doc/ISSUE_TRACKER.md` 的职责
- 不给排期，只给依赖顺序
- 不改 `DatabaseDialect` 现有 15 个方法的签名，不改 `DatabaseMetadataService` 的 9 个公开方法签名

---

## 附录 A：证据核对（HEAD `3b18b2f`）

| 结论 | 核对命令 |
| --- | --- |
| `DatabaseType` 无字段 | `src/main/java/.../driver/DatabaseType.java:3-5` |
| `ConnectionProvider` 只有 2 个方法 | `src/main/java/.../driver/ConnectionProvider.java:10-20` |
| 元数据服务 486 行、入参仅 provider | `wc -l` + `grep "public .*(" DatabaseMetadataService.java` |
| 判断分支 16 处 | `grep -n "product\|getDatabaseType()\|DatabaseType\." DatabaseMetadataService.java JdbcDatabaseStatisticsReader.java MetadataQuery.java DatabaseConnectionInfo.java JdbcViewMigrator.java` |
| `DB2 \|\| DM` 大写 | `DatabaseConnectionInfo.java:79-81` |
| 视图名大写 4 处调用（3 个方言） | `grep -rn "toUpperCase(Locale.ENGLISH)" dialect/*.java` |
| `commentLiteral` 三份逐字符相同 | 三文件 48-50 / 49-51 / 75-77 行比对 |
| `lastIdentifier` 三份逐字符相同 | PG 30-41 / Kingbase 31-42 / DB2 41-52 行比对 |
| surefire 默认排除 IntegrationTest | `pom.xml:98-101`，集成档 `pom.xml:106-124` |

## 附录 B：实测记录（实施阶段填写）

| 阶段 | 提交 | MYSQL | MARIADB | GBASE | ORACLE | SQLSERVER | PG | DB2 | DM | KINGBASE | 快照哈希 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| P-0 | | | | | | | | | | | | |
