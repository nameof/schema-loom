# 问题台账（Issue Tracker）

代码审查发现的缺陷与优化项跟踪表。

**更新规则**：每解决一条，把该条从「待办」移到文末「已解决归档」并标注解决日期；部分解决的留在
「部分解决」区并写明残余问题。待办区里剩下的，一律是还没动的。

**最后更新**：2026-11-25（E5 解决：`Target.close(commit)` 删除内部 writeFailed 否决、无条件服从，无参 `close()` 保留保守默认防 try-with-resources 误提交，SKIP_BATCH+REPLACE 经决策维持 PARTIAL 提交并文档化，详见第四节归档）；
此前 2026-11-24（D1 部分解决：探测 Schema 改用 `setMaxRows(1)`，7 库实机对比后否决台账原列的两条建议修法，残余与依据见第三、五节；同日 D6 经反编译核验为非缺陷，移入已验证非缺陷区；D9 撤销单独立项，产品名重复嗅探并入 E8、正则卫生项并入 E13，待办移除；**G 组整体移除**——G1 已解决归档删除、G2 / G3 未立项即移除；**D2 移除**——项目定位为只保证 API 调用边界，不深入定制各 JDBC 驱动私有逻辑，设计边界记录见第七节附带说明；**E7 移除**——长表名超方言标识符上限属极端场景，暂不投入，聚焦主要问题优化，后续如有人反馈或实际遇到再立项）；
此前 2026-11-21（A 组提交模型重构为 `close(boolean commit)` 单一决策点，归档与接口影响以最终形态改写；此前同日合并 `8ad61b9f..HEAD` 多库方言补丁集审查结论，逐条核验记录见第八节）

---

## 一、进度总览

| 分组 | 待办 | 部分解决 | 已解决 | 已验证非缺陷 | 小计 |
|---|---|---|---|---|---|
| A 数据丢失 / 状态误报 | 0 | 0 | 4 | 0 | 4 |
| B 各数据库特有分支 | 3 | 1 | 9 | 2 | 15（新增 B14 下推黑白名单相反、B15 视图定义查询无条件大写） |
| C 类型系统与编解码 | 2 | 0 | 8 | 1 | 11（新增 C10 布尔 DDL 与绑定类型码分裂、C11 coerce 静默收敛） |
| D 性能与资源 | 3 | 1 | 2 | 1 | 7（D1 部分解决；D6 核验为非缺陷归档；D9 并入 E8 / E13、D2 因项目边界决策移除，待办仅剩 D3 / D5 / D8） |
| E 设计与耦合 | 8 | 0 | 4 | 0 | 12（新增 E8~E14；E7 移除；E5 解决归档：`close(commit)` 无条件服从 + 无参 `close()` 保守默认） |
| F 元数据与文档不一致 | 2 | 0 | 2 | 0 | 4 |
| **合计** | **19** | **2** | **28** | **4** | **53** |

> 2026-11-21 修正计数口径：原表「已解决 28」把 C5 算进去了，而归档表实际只有 27 行；
> B 组小计把 B6 / B7 算进小计却没算进任何一列，导致「合计 46」与「15+1+28=44」对不上。
> 现拆出「已验证非缺陷」独立一列（B6 / B7 / C5），四列相加等于小计，逐行可核。

**建议处理顺序**

1. **正确性优先**：B12（SQL Server 按表名查询落空）、**B15**（小写视图迁移必失败）
2. **先验证再动手**：B14（KingbaseES 表名下推未实测）、C10（老版 ojdbc 布尔 null 绑定未验证）、
   E10（位置对齐未构造出触发场景）—— 见第六节
3. **结构性重构**：E8 → E9 → E10 → E11 / E12（E8 是 B14 / B15 的共同根因，D9 的产品名重复嗅探也已并入本条，
   不收敛就会继续按补丁堆叠）
4. **D / F / 小清理**（E13 / E14）可批量做

> A 组已于 2026-11-11 解决，2026-11-21 重构为「`close(boolean commit)` 单一决策点」模型
> （abort 负向标记从未提交发布，直接以最终形态归档），详见第四节归档。

**依据列含义**

- `推断`：从数据库语法规范或代码逻辑推导，**未经实机验证**
- `实证`：已在真实数据库容器或单元测试中验证
- `存疑`：结论本身待确认，可能不是真 BUG

> 2026-10-28 用本地 Podman 容器（DB2、达梦）实机验证了 B5 / B6 / C1 / B8，
> 详见第五节「实机验证记录」。其中 **B6 被实机推翻——不是 BUG**。

---

## 二、待办清单

### A 组：会真正丢数据 / 状态误报

**本组已全部解决**（2026-11-11，A1~A4 一次改动统一处理）。明细见第四节归档。

### B 组：各数据库特有分支缺失 / 写错

| 编号 | 位置 | 问题与后果 | 建议修法 | 依据 |
|---|---|---|---|---|
| B12 | `DatabaseMetadataService#catalog` + SQL Server | 连到 `master` 的会话，SchemaCrawler 只返回 1 张表且是 `dbo.user_info`（该表实际在 `sl_source` 库），master 里真实存在的 12 张 `sl_*` 表一张都没返回；`listTables` 用 `sl%`、`sl_null_src` 等模式一律 0 张 → 按表名查询落空，SQL Server 作为 Source/Target 时目标表被判为「不存在」。**改动前基线同样复现**，不是 C7/B11 改动引入 | 先确认 SchemaCrawler SQL Server 插件用哪个 catalog 枚举表（疑似用登录默认库或扫描到的第一个库，而非连接的当前库）；必要时与 Oracle 一致，在 SQL Server 上不依赖插件枚举，或显式指定 catalog | **实证**：SQL Server 2022 容器，`SELECT DB_NAME()` 返回 master，`listTables('%')` 只返回 dbo.user_info |
| B14 | `DatabaseMetadataService#pushesTableFilter`(211-215) / `#pushesSchemaFilter`(179-184) / `MetadataQuery#fromConnectionInfo`(35-38) | 同一件事两套相反策略：表名下推是**黑名单**（产品名不含 oracle 就放行），schema 下推是**白名单**（只放 sql server / `dm dbms` / db2）。KingbaseES 会被放行做表名下推，而同文件 `MetadataQuery` 自己写着「KingbaseES 兼容 PostgreSQL 但 SchemaCrawler 的 schema 模式匹配不兼容」。后果：KingbaseES 上 `includeTables` 一旦像 Oracle 那样被插件误解析，精确表名查询就会落空 → 目标表被判为不存在、重复建表（与 B11 同一类致命路径）。两处对「读不到产品名」的处理也不一致（表名侧放行、schema 侧保守） | 把「能否下推表名 / schema」收敛成同一份方言能力表（见 E8）；KingbaseES 在未实测前按 Oracle 处理（不下推表名），读不到产品名时统一按「不下推」保守处理 | **存疑**：策略相反已实证；KingbaseES 的实际后果未实机验证 |
| B15 | `Db2Dialect#viewDefinitionQuery`(46-50)、`DmDialect#viewDefinitionQuery`(41-42)、`OracleDialect#viewDefinitionQuery`(42-43) | B5 让 `quote()` 不再改写大小写，并在 `DatabaseConnectionInfo#normalizeIdentifier`(68-76) 留了「双引号包裹即精确保留大小写」的逃生口；但读视图定义时仍无条件 `toUpperCase` 去查 `SYSCAT.VIEWS` / `ALL_VIEWS`。用 `"myview"` 传入时表数据路径能取到对象、视图迁移路径查不到 → `JdbcViewMigrator#readDefinition` 抛「view definition is unavailable」（错误信息指向「检查权限」，误导）。同一决策只落了一半，三个方言同病 | 视图名与 owner 一律按入参原样使用，不再大写；需要「未加引号即大写」的场景由 `normalizeIdentifier` 在输入层完成 —— 与 B5 的分层保持一致 | **推断**：B5 已实证「加引号小写对象只能精确大小写访问」，本机容器未启动，未实机复跑 |

### C 组：类型系统与编解码

C1~C9 已处理完（C5 实机验证为非缺陷），明细见第四节归档；2026-11-21 新增两项待办：

| 编号 | 位置 | 问题与后果 | 建议修法 | 依据 |
|---|---|---|---|---|
| C10 | `OracleDialect#mappings`(51) + `JdbcTableTarget#nullSqlType`(316-320) + `DatabaseTypeMapping` | BOOLEAN 在 Oracle 建成 `NUMBER(1)`，但声明用的是 `supported()` 而非 `degraded()`，`degradesTo` 为 null → null 绑定回落到 `Types.BOOLEAN(16)`。23.26 上 `setNull(16)` 实测通过，风险集中在 23ai 之前的 ojdbc（驱动不保证对非 BOOLEAN 列接受 16）。结构性缺口：`DatabaseTypeMapping` 只能表达「降级」，无法表达「DDL 用 A、绑定按 B、但语义不降级」，`JdbcValueCodec#degradeValue`(56-63) 也只覆盖 `Offset*` 两种 | 给 `DatabaseTypeMapping` 增加「绑定类型码覆盖」（如 `bindAs(int)`），BOOLEAN→NUMBER(1) 声明为绑定 INTEGER；或至少把 19c 及更早的 ojdbc 列入兼容矩阵实测 | **存疑**：23.26 未复现，老驱动未验证 |
| C11 | `LogicalTypeCatalog#coerce`(51-65) | 收敛全程无日志。`LocalDateTime→LocalDate` 截断时分秒已是 C9 登记的遗留取舍；数值侧 `BigDecimal→Float/Double` 在 Oracle `FLOAT`（两套元数据判定不同）这类场景也会静默改精度 —— 数值收敛本身不算缺陷（FLOAT64 列就是双精度），但**无法从日志判断到底有没有发生过收敛**，与 B8「降级必 warn」、`JdbcTableTarget#validateCapabilities` 的可观测原则不一致 | 在 `coerce` 真正改变对象类型时打 debug（同一字段首次 warn 即可），不改变收敛行为 | 推断 |

### D 组：性能与资源

| 编号 | 位置 | 问题与后果 | 建议修法 | 依据 |
|---|---|---|---|---|
| D3 | `XlsxSource` | `recordSchema()` 开一次工作簿，`read()` 又开一次 → 大文件双倍内存 + 双倍解析 | 复用工作簿，或改流式读取（XSSF eventmodel） | 推断 |
| D5 | `JdbcDriverLoader#TcclInvocationHandler` | 每次 JDBC 调用都要切 TCCL + 反射 invoke，且 ResultSet 被递归代理 → 百万行场景开销明显 | 缩小代理范围到连接级别，或按驱动白名单决定是否代理 | 推断 |
| D8 | `DatabaseMetadataService#listTables` | 列表路径为了返回 `TableInfo`，把每张表的列、索引、外键、约束全部抓一遍（默认 InfoLevel）。而数据资产探索的表树只要表名/注释/类型 —— DB2 上 343 张表跑 24 秒，其中绝大部分时间花在下游根本用不到的索引与外键检索上 | 列表路径用轻量 InfoLevel（只带表与列，不含索引/外键/约束），详情改由 `getTableDetail`/`listIndexes` 按表按需取 | **实证**：DB2 BLUDB，`IndexRetriever` 按表逐个刷屏，耗时与实例表总数成正比 |

### E 组：设计与耦合

| 编号 | 位置 | 问题与后果 | 建议修法 | 依据 |
|---|---|---|---|---|
| E1 | 全局 | `loader → provider → connection → source/target → task` 五层套娃（README 自己的「优化 TODO」第一条）。同一连接被 `SingleConnectionDataSource` 包了 2-3 次（含 D6 核验后并入的统计 reader 重复包装：`getTableStatistics`/`getSchemaStatistics` 每次调用都新建包装并重复读产品名） | 合并 provider/connection 层，DataSource 只包装一次 | 推断 |
| E6 | `DatabaseMetadataService#listTables`(72) vs `#sqlPattern`(251-260) | 表名转正则用 `replace("%",".*").replace("_",".")`，**没有 `Pattern.quote`**；同文件 `sqlPattern()` 却做了 quote → 两套标准。2026-11-21 补充两条后果：①`_` 被当成「任意一个字符」而不是字面下划线 —— 项目自己的测试表就叫 `user_info`，下推路径（`sqlPattern`）与本地过滤路径（第 72 行）因此不是同一批表，`user_info` 会额外命中 `userXinfo` 之类；②表名含 `.` `(` `[` 时误匹配或 `PatternSyntaxException` | 用 `Pattern.quote` 转义字面部分；最好让下推与本地过滤**共用同一个** `sqlPattern()` 实现，避免再次分裂 | 推断 |
| E8 | `DatabaseType`（无字段）、`AbstractDialect#supportsEmptySchemaSegment`(45, `protected`)、`DatabaseMetadataService`(179-184 / 211-215 / 220-225 / 340-345 / 379-382)、`MetadataQuery`(25-42) | 方言能力知识**三处分裂**：①枚举只有名字，没有「是否三级命名 / 是否接受空模式段 / 名字别名」等字段；②`supportsEmptySchemaSegment` 是 `protected`，metadata 包调不到，只能在本地用产品名字符串重新判断；③同一「达梦」在不同处写成 `"dm dbms"` 与 `"dm"` 两种形态，各自靠 `contains` 撞运气。后果：每新增一个库种，能力判断要在 4~6 处同步改，漏一处就是「某库偶发查不到表」。根因是 `DatabaseMetadataService` 只收 `ConnectionProvider`，拿不到 `DatabaseType` | 给 `DatabaseType` 加能力字段（或由 `DialectRegistry` 暴露 `DialectCapabilities`），`DatabaseMetadataService` 改为接收方言 / 类型，不再自己嗅探产品名。B14 / B15 是它的下游；2026-11-24 起原 D9 的产品名重复嗅探（一次 `listTables` 取 3 次 + `enrichGeneratedColumns` 每表 1 次）也并入本条，随本条修复自然消解 | 实证 |
| E9 | `DatabaseMetadataService`（486 行，全部 7 类职责） | 七类职责混在一个类：SchemaCrawler 兼容（`System.setProperty` + 进程级锁）、下推策略、命名空间正则、类型归一、精度修正、生成列旁路 SQL、索引/外键/约束映射。附带 R7：`UNKNOWN_TYPE_NUMBER = Integer.MIN_VALUE`(33 / 304) 直接依赖 SchemaCrawler 对未收录类型的**内部约定值**，升级 SchemaCrawler 后「未识别类型」唯一的可观测点（warn）会静默失效 | 至少拆成 MetadataLoader（crawl + 兼容）/ MetadataFilter（下推与匹配）/ TableInfoMapper（归一与映射）/ GeneratedColumns 四块；UNKNOWN 判据改成自证条件（`JdbcTypes` 认不出且类型名不在白名单），不依赖魔法值 | 实证 |
| E10 | `JdbcTableSource#align`(201-209)、`JdbcQuerySource#readSchema`(108-121)、`JdbcTableSource#ensureTableInfo`(126-131) | 双元数据源（驱动 `ResultSetMetaData` vs SchemaCrawler 表元数据）只在出口打补丁。`align` 按**位置**而非列名对齐委托记录：列少补 null、列多直接丢弃，同类型列错位时 `DataRecord` 校验完全无声 → 可能静默写错整列。`SELECT *` 目前能保证顺序一致，所以尚未构造出触发场景，但这是安全缺口，且与 `FieldMapping#mapRecord`（按名取值）风格相反。每行还背着 3 次 `DataRecord` 构造 / 3 次 List 拷贝 / 2 次 `coerce` 的固定开销 | 给 `JdbcQuerySource` 加「权威 Schema」入口（传入表元数据 Schema，按列名映射而非下标），`align` 退化为断言；做不到时至少在 `align` 校验列名序列一致，不一致显式失败 | 实证（代码行为已确认，触发场景未构造，见第六节） |
| E11 | `DatabaseConnectionInfo#normalizeIdentifier/uppercasesIdentifiers`(64-81)、`SqlServerDialect#unquoteQualifiedName/segments/lastSegment`(54-91) | 标识符处理散落在 3~4 处且互不知情：①连接配置类承担了「DB2 / 达梦大写」的方言知识，引号字符硬编码 `"`；②只规范化 table 段，catalog / schema 段原样透传；③`SqlServerDialect` 自己一套「先剥引号再切分」，`"a.b"."t"` 被切成 `a.b.t` 三段再取 `b.t` —— 引号内点号的保护正好在切分前被销毁；④B13 已把 `lastIdentifier` 下沉到 `AbstractDialect`，SqlServer 仍在用自带的 `lastSegment`。D6「该用 Hutool」的指控需先确认 Hutool `split` 能否保留空段（能则一并替换） | 抽一个 Identifier 工具（quote / unquote / split 保空段 / lastSegment / 大小写策略），方言只声明 `quoteChar` 与大小写策略；`normalizeIdentifier` 下沉到方言层 | 实证（Hutool 替换可行性待确认） |
| E13 | `DatabaseMetadataService#join`(262-269)、`#loadCatalogWithCompatibility`(225)、`JdbcTableTarget` 构造器(53-82) | 小清理集合：①`join()` 全项目零调用（死代码）；②`contains("db2") ? "db2" : contains("kingbase") ? "postgresql" : "postgresql"` 后两个分支相同，恒等三元让人误以为有第三种取值；③`JdbcTableTarget(provider, info, table, type)` 里 `info` 已含 `type`，两处可能不一致；④`openValidated` 里 `info.table(table)` 只为校验、返回值被丢弃，紧接着构造器又调一次；⑤（2026-11-24 原并入 D9）`LogicalTypeCatalog#excelTime` / `#excelDate` 用 `String.matches(...)`，每次调用重新编译同一个正则 | 删 `join`；三元压平成二分支；构造器去掉 `type` 参数（取 `info.getDatabaseType()`）；校验与构造复用同一次 `table()` 结果；正则提为 `static final Pattern` | 实证 |
| E14 | `MetadataQuery`（无 `equals/hashCode/toString`）、`DatabaseMetadataService#getTable(provider, MetadataQuery)`(93-97) | `getTable` 把 `query.getTablePattern()` 当**精确表名**用（`equalsIgnoreCase` 比较），而 `listTables` 把同一个参数当 **LIKE 模式**；传 `S%` 给 `getTable` 会得到「table not found」而不是「参数非法」。`MetadataQuery` 又缺 `equals/hashCode/toString`，作值对象在日志和测试断言里只能看到对象地址 | `getTable` 显式拒绝含 `%` / `_` 的模式，或改收 `QualifiedTableName`；补 `equals/hashCode/toString` | 实证 |

### F 组：元数据与文档不一致

| 编号 | 位置 | 问题与后果 | 建议修法 | 依据 |
|---|---|---|---|---|
| F1 | `DatabaseMetadataService#catalog` | `query.getCatalog()==null` 时 **schemaPattern 直接为 null，不下推**。Oracle/DB2 场景 catalog 通常为空 → 全库扫元数据（慢且大库易超时），靠下游 `matches()` 硬过滤 | catalog 为空时按方言把 database 作为 catalogPattern 下推 | 推断 |
| F3 | README catalog/schema 表 | 说「DB2 / DM 未指定 Schema 时回退到数据库名」，但 `DatabaseConnectionInfo` 只对 `POSTGRESQL`/`KINGBASE` 默认 `public`，DB2/DM 没实现 | 要么实现回退，要么改文档 | 实证 |

---

## 三、部分解决

| 编号 | 位置 | 已修部分 | 残余问题 | 依据 |
|---|---|---|---|---|
| B3 | `SqlServerDialect#commentSql` | 已修：①`@level0name` 不再硬编码 `dbo`，从目标限定名解析架构；②REPLACE 模式下注释落到 `<表>_tmp` 而不是源表名（原本会加错对象或丢失） | 回退链第 2 步用的是**源表** schema。源/目标 schema 不同时仍会加错。彻底解法是给 `commentSql` 传目标连接信息 —— 那是 `DatabaseDialect` 接口级改动，波及 9 个方言，未做 | 推断 |
| D1 | `JdbcQuerySource#recordSchema` | 探测查询改传 `maxRows=1`，不再把整条 SQL 的结果集全量回传；7 库实机验证元数据与现状逐字段一致，正式 `read()` / `preview()` 的行数语义未变。新增单测 `JdbcQuerySourceInspectTest` 锁定三条路径；全量 `mvn test` 156 项通过 | ①`setMaxRows` 只截客户端回传行数，**服务端仍执行完整查询计划**（大表扫描 / ORDER BY / 聚合照跑），是缓解非根治；②`JdbcTableSource` 已有权威 `TableInfo`，却仍委托 `JdbcQuerySource` 跑一次 inspect，表源路径根治需 E10 的「权威 Schema 注入」；③彻底不执行需 `PreparedStatement#getMetaData()` 分层方案，但本次按评审决策采用最小改动 | **实证**：7 库 56 组元数据对比 + 16384 行大表收益探针，见第五节 |

---

## 四、已解决归档

| 编号 | 问题摘要 | 修法 | 解决日期 |
|---|---|---|---|
| B1 | MySQL / GBase 缺 MariaDB 已有的 PRIMARY 索引过滤 | 与 `#329` 收敛：把保留名判据下沉合并进 `DatabaseMetadataService#isPrimaryKeyIndex`（OR 判据），删除方言层三个 `indexSql` override。**原 BUG 定性存疑，见第六节** | 2026-10-17 |
| B2 | MariaDB DECIMAL scale 上限写成 38（MySQL 系实际是 30） | 改为 30。连带修正 `MariaDbContractTest` 把错的 `DECIMAL(65,38)` 固化成断言的问题（照着代码写测试而非照规范写） | 2026-10-17 |
| B4 | SQL Server `CHARINDEX` 落空时 `SUBSTRING` 从第 4 字符截取，静默产出错位视图定义 | 改为 `CASE WHEN ... THEN ... END`，落空返回 NULL，交给下游「视图定义不可用」分支显式报错；加 `UPPER` 兼容区分大小写排序规则 | 2026-10-17 |
| B5 | DB2 / 达梦 `quote()` 无条件 `toUpperCase`，库里已存在的加引号小写对象访问不到 | `quote()` 不再改写大小写（元数据返回什么就引用什么）；「新建对象沿用大写」前移到 `DatabaseConnectionInfo#table` 的输入层规范化，并保留双引号包裹作为精确保留大小写的逃生口。**2026-10-29 补修**：`Db2Dialect#renameTableSql` / `DmDialect#renameTableSql` 里残留的「大写后重建」与新 `quote()` 不一致，导致 REPLACE 时临时表建成 `"T_tmp"` 而 rename 去找 `"T_TMP"` → 对象不存在、整个 REPLACE 失败（详见第五节） | 2026-10-28（DB2 / 达梦实机验证），2026-10-29 补修回归 |
| B8 | `OFFSET_*` 降级为无时区类型时完全静默，时区信息无声丢失 | `DatabaseTypeMapping` 增加 `degradesTo`，`JdbcTableTarget#validateCapabilities` 检测到降级即 `log.warn`，不再静默 | 2026-10-28（DB2 实机验证） |
| B10 | MySQL 系注释 SQL 只转义单引号、未转义反斜杠（`\` 是 MySQL 字符串转义符） | `MySqlDialect` / `MariaDbDialect` / `GBase8aDialect` 的 `commentSql` 统一先转义 `\` 再转义 `'` | 2026-10-28（单测覆盖，真实 MySQL 系库待验） |
| C1 | 方言降级了 DDL 但 JDBC 绑定没降级，DB2 报「无法识别的 JDBC 类型 2013」（ERRORCODE=-4228） | 绑定跟随降级：`DatabaseTypeMapping#degradesTo` + `JdbcValueCodec#write` 接受方言并按降级目标转换值与类型码；`JdbcTableTarget#nullSqlType` 让 null 绑定同样跟随 | 2026-10-28（DB2 实机验证） |
| B9 | `IDENTITY_INSERT` 的判定依据是**源表**而非实际写入的目标表。源表有自增列、目标表没有时，APPEND 仍执行 `SET IDENTITY_INSERT`，整个批次失败 | 新增 `writeTableHasIdentity`：由「实际写入的那张表」判定（APPEND 到已存在目标表时用目标表元数据；目标表或临时表由源表新建时才用源表）。判定同时检查该自增列是否真的在写入列集合中 | 2026-10-29（达梦实机验证） |
| C2 | `OracleDialect` 把 BOOLEAN 建成 `CHAR(1)`，而驱动把布尔绑成原生布尔字面量 → 隐式转成 `'TRUE'/'FALSE'` 后 `ORA-12899`（值太大），写入即失败 | DDL 改为 `NUMBER(1)`（Hibernate 在 Oracle 上的经典布尔映射）。实测写入 true/false/null 分别存 `1/0/NULL`，`getObject(Boolean.class)` 读回 `true/false/null` 全部正常 | 2026-10-29（Oracle 23.26 实机验证） |
| E4 | `FieldMapping#mapTableInfo` 对源表里不存在的映射字段 `continue` 静默跳过，而 `mapSchema` 抛异常 → 目标 Schema 少列，`mapRecord` 仍按完整映射列表取值，最终在 `DataRecord` 构造时报 "value count differs from schema"，错误点离真正原因很远 | 统一为显式失败：`mapTableInfo` 列缺失改抛 `IllegalArgumentException("源表中不存在映射字段: <name>")`，与 `mapSchema` 以及本方法内索引/主键/外键/约束的处理一致。`mapRecord` 未动（按评审意见保持改动最小）。新增单测 `rejectsMappingToMissingSourceColumn` | 2026-11-03 |
| F2 | README「数据库到数据库」章节写 `tableSource.read()` / `read(200)`，**实际方法名是 `preview(int)`**，`read(BatchConsumer)` 是另一个签名 → 文档跑不通 | 改为 `preview(100)` / `preview(200)`，并注明行数上限为 `MAX_PREVIEW_ROWS`（300）；`read(BatchConsumer)` 读全表的说明保持不变 | 2026-11-03 |
| F4 | README 逻辑类型清单只列到 `BINARY`，漏了 `OFFSET_TIME` / `OFFSET_TIMESTAMP`；且 MySQL 列注释明确不迁移，文档没写清 | 清单补齐两种带时区类型并说明降级时会告警（B8）；方言章节新增一段说明各库注释迁移能力差异 —— MySQL / MariaDB / GBase 8a 只迁表注释，列注释仅采集不写回 | 2026-11-03 |
| C4 | `LogicalTypeCatalog#time` 的 Excel decode 只做 `LocalTime.parse(String.valueOf(raw))`，日期列有 `excelDate()` 兜底而时间列没有 → Excel 时间单元格（序列号 0.5）解析必失败 | 新增 `excelTime(raw)`：Date 走 `excelDate`，序列号与数字字符串走 `DateUtil.getLocalDateTime(double)`，纯时间文本仍直接 `LocalTime.parse`。**不能复用 `excelDate`** —— 它经 `getJavaDate` 生成 Date 再按默认时区还原，在 1899 这个基准年份会命中中国时区 1901 年前的 LMT 偏移（+08:05:43），把 12:00 变成 12:05:43（单测实测）。新增用例 `decodesExcelTimeFromSerialNumber` | 2026-11-04 |
| E3 | `MetadataQuery#fromConnectionInfo` 与主流程「按名称精确查询」两套命名空间语义并存，它实际只有测试在用；主流程 MySQL 系未把 database 下推为 catalog，元数据退化为全库扫 | 主流程统一走 `fromConnectionInfo`：新增 `fromConnectionInfo(info, QualifiedTableName)`（表名自带 catalog/schema 优先、其余回退方言默认值，info 为 null 时不推导），`JdbcTableSource` / `JdbcTableTarget`（3 处）/ `JdbcViewMigrator`（2 处）全部改用它；`DatabaseMetadataService` 增补 `getTable(provider, MetadataQuery)`。**同时收紧兜底**：删掉「catalog 与 schema 都为空时把 database 当 catalog」这条分支 —— Oracle 的 database 是服务名、达梦是实例名，下推后命名空间过滤会剔除全部 schema，表直接查不到。**2026-11-06 补修**：PostgreSQL 分支当时仍把 database 当 catalog 下推，而命名空间过滤是拿 schema 名去匹配 SchemaCrawler 的「库名.public」全名，模式 `(?:^|.*/)public(?:/.*)?$` 匹配不上 → 精确表名查询命中 0 条（真实 PG 16 容器实测），目标表被误判为不存在。改为只透传显式配置的 catalog，未配置时保持 null | 2026-11-04，2026-11-06 补修 |
| C3 | `Types.FLOAT → FLOAT32` 把双精度列降级成单精度。**原判断的受害库搞错了** —— SQL Server 的 `float` 实际上报 `Types.DOUBLE(8)`、`float(24)`/`real` 上报 `REAL(7)`，不受影响；真正的受害库是**达梦**（`FLOAT` 上报 6，双精度）和 **Oracle**（`FLOAT` 上报 6，二进制精度 126 位） | `JdbcTypes#logical` 改为 `Types.FLOAT → FLOAT64`、`Types.REAL → FLOAT32`（与 JDBC 规范一致：FLOAT 对应 double）。达梦实测：修复前 `FLOAT` 存 `1.23456789012345` 读回 `1.2345679`（Float），修复后读回 `1.23456789012345`（Double），`REAL` 仍为 `1.2345679`。新增单测 `JdbcTypesTest#mapsFloatToFloat64` | 2026-11-06 |
| C6 | null 绑定沿用**源列**的 `jdbcType` 调 `setNull`，跨库时源类型码在目标驱动上非法 | 改为按「逻辑类型在目标方言下的类型码」绑定：`nullSqlType(field)` 取 `dialect.mapping(...).degradesTo()`（无降级则用逻辑类型本身）对应的 JDBC 码。实测各目标库对全套逻辑类型标准码只有 DB2 拒绝 2013（已有降级逻辑覆盖）。回归：PG `uuid`(1111) → Oracle、SQL Server `nvarchar`(-9) → PostgreSQL 两条链路由失败转成功 | 2026-11-06 |
| C7 | Oracle `BINARY_FLOAT` / `BINARY_DOUBLE` 被当成 `STRING`。**原判断的机制记错了**：驱动确实上报厂商码 100 / 101，但 SchemaCrawler 未收录这两个码，归一化成 `UNKNOWN`（`Integer.MIN_VALUE`），落进 `JdbcTypes` 的 default 分支才变成 STRING。同一批列的 `FLOAT` 列在驱动侧是 NUMERIC(2)、在 SchemaCrawler 侧是 FLOAT(6)，两套元数据对同一列给出不同逻辑类型 | 新增 `JdbcTypes#logical(int, String)`：先按类型名把 `BINARY_FLOAT` / `BINARY_DOUBLE` 映射到 FLOAT32 / FLOAT64，其余交回按码判定；`DatabaseMetadataService#map` 与 `JdbcQuerySource#readSchema` 两条路径都改用它，UNKNOWN 且仍未识别的类型记 warn 日志（保留 STRING 行为，不静默）。实测 Oracle 23.26：BF→FLOAT32、BD→FLOAT64、FL→FLOAT64，目标 DDL 均为 FLOAT；Oracle→PostgreSQL、Oracle→达梦 两条真实 ETL 链路 `SUCCESS written=1`，落地值 `BF=1.2345679(Float)`、`BD=1.23456789012345(Double)` | 2026-11-07 |
| C8 | Oracle 未指定精度的 `NUMBER`（如 `GENERATED AS IDENTITY` 主键）在 SchemaCrawler 里是 `size=0`、`decimalDigits=-127`，`FieldSchema` 抛 `scale must be non-negative` → **整表元数据读取失败**；驱动的 `ResultSetMetaData` 对 `FLOAT` 列也报 `scale=-127`，`JdbcQuerySource` 建 Schema 同样被打挂 | ①`DatabaseMetadataService#map`：负的 `decimalDigits` 与非正的 `size` 一律按「未指定」置 null；②`JdbcQuerySource#readSchema`：`getColumnDisplaySize/getPrecision/getScale` 的负值同样置 null；③`LogicalTypeCatalog#coerce`：驱动不按请求类型转换时（Oracle FLOAT 的 `getObject(i, Double.class)` 返回 BigDecimal）按目标类型收敛数值；④`JdbcTableSource#align`：把委托记录的值收敛到表元数据的 Schema。实测：`listTables` 不再抛异常，`USER_INFO.ID` 正常输出 NUMBER(10,0)，迁移后 BF/BD/FL/RL 精度全部正确 | 2026-11-07 |
| C9 | Oracle `DATE` 列在预览 / ETL 时抛 `logical type DATE requires LocalDate but received LocalDateTime`。根因是两套元数据对同一列判定不同：SchemaCrawler 把 `DATE` 判成 `DATE`（要求 `LocalDate`），驱动的 `ResultSetMetaData` 却把 `DATE` 报成 `TIMESTAMP`（读出 `LocalDateTime`），`JdbcTableSource#align` 收敛时被 `DataRecord` 的值校验挡住。dbmask 的数据预览（`ResourceExploreServiceImpl#getTablePreview`）正是这条链路 | `LogicalTypeCatalog#coerce` 补时间家族分支：目标逻辑类型是 `DATE` 而驱动给出 `LocalDateTime` 时截断成 `LocalDate`，其余情况（null、已是目标类型、无关类型）行为不变。**遗留取舍**：Oracle `DATE` 本身带时分秒，截断后时分秒会静默丢失；本次按评审决策不改 `DATE → TIMESTAMP` 的类型映射（改了会让 Oracle→Oracle 建表列型从 `DATE` 变 `TIMESTAMP`），需要保真时另开条目 | 2026-11-07（实证：`SL_SOURCE.USER_INFO.CREATED_AT` 修复前抛上述异常、修复后预览返回 `LocalDate`；反向验证注释掉该分支即复现原报错） |
| B11 | Oracle 上 `includeTables` 的表名模式除 `%` 之外全部落空（`S%` / `%SL%` / `%FLOAT%` / 精确表名都是 0 张），目标表存在也被判为不存在 → Oracle 的 APPEND / REPLACE 实际不可用 | `DatabaseMetadataService#pushesTableFilter`：Oracle 上不再下推 `includeTables`（保留 `includeSchemas` 下推），由 `listTables` 内的 `matches` / `pattern` 做精确过滤。**读不到产品名（`productName()` 返回 null）时同样不下推**，与 `pushesSchemaFilter` 的保守策略一致——放行只是变慢（`pattern` 兜底保证结果正确），漏判会让 Oracle 重新踩进这条致命路径。怀疑是 SchemaCrawler 的 Oracle 插件把正则当成表名模式去查系统表。实测修复后 `%`→2、`S%`→1、`%FLOAT%`→1、精确表名→1，`EtlTask` 不再报 `table not found` | 2026-11-07（2026-11-11 补 `product == null` 分支加固） |
| D7 | DB2「只配 schema」时不下推 `includeSchemas` → 每次取表都全实例扫描（DB2 容器实测 7 个 schema / 343 张表、23.6 秒），控制台被 `schemacrawler.IndexRetriever` 按表刷屏，数据资产探索页等 20 秒以上才出表。原排除理由「DB2 默认 schema 由连接会话决定、过滤语义不稳定」经实测不成立（未配置 schema 时 `fromConnectionInfo` 传的就是 null，根本不会走到下推） | `DatabaseMetadataService#pushesSchemaFilter` 白名单加 DB2（按产品名含 `db2`）。实测：`listTables(schema=SL_SOURCE)` 23.6 秒 → 0.64~0.9 秒，返回仍只有 `SL_SOURCE.USER_INFO`；DB2 全量迁移集成测试（DDL/默认值/视图）通过。配套把 dbmask `logback.xml` 的 `schemacrawler` 日志级别压到 WARN | 2026-11-08（实证：DB2 BLUDB 容器，`Db2SchemaPushdownProbeIntegrationTest`） |
| A1 | `JdbcTableTarget#close` 先 DROP 原表再 RENAME 临时表，catch 里又删临时表 → rename 失败即双丢 | 换表顺序改为「原表 rename 为 `<表>_old` → 临时表接位 → 删 `_old`」，任何一步都不先 drop 原表。接位失败时把 `_old` 改回原名、临时表一律保留 | 2026-11-11（2026-11-14 回退为 close 即提交，顺序修正保留） |
| A2 | `targetWriteFailed` 只在 `write()` 置位，源读异常 / `Future.cancel` 中断时仍提交，半份数据替换全表 | `EtlTask` 以 `commit = (状态为 SUCCESS/PARTIAL)` 单点决策：源读异常、中断、读少时任务为 `FAILED`/`CANCELLED`，以 `close(false)` 关闭目标，target 据此只清理临时表。读少额外记 `stage=read` 错误并置 `FAILED` | 2026-11-11（2026-11-21 重构为 close(commit)；单测：`skipsCommitWhenSourceReadFails`、`skipsCommitWhenReadFewerRowsThanDeclared`、`cancelledDoesNotCommit`、`writeFailureFailFastDoesNotCommit`） |
| A3 | `target.close()` 抛异常只 `addError`，返回 `SUCCESS` 但表没换 | `close()` 抛异常时把 `SUCCESS`/`PARTIAL` 降为 `FAILED`（close 即提交模型下，换表失败即由 close 抛出） | 2026-11-11（单测：`closeFailureTurnsSuccessIntoFailed`） |
| A4 | `XlsxTarget` 的 `failed` 只在 `write()` 置位，读阶段失败仍会把 `.part` 覆盖正式文件 | `XlsxTarget` / `CsvTarget` 在 `close(commit)` 时移动 `.part`，commit 为 false（读失败、中断、读少）时只关闭流并保留 `.part`，正式文件保持原样。`CsvTarget` 同构补齐（原实现只在写失败时保留 `.part`） | 2026-11-11（单测：`keepsExistingWorkbookWhenReplaceIsNotCommitted`、`keepsExistingFileWhenReplaceIsNotCommitted`） |
| B13 | `OracleDialect` 没有覆盖 `renameTableSql`，落到 `AbstractDialect` 的默认实现（新名原样拼接全限定名）。而 `JdbcTableTarget` 传的正是 `quote()` 后的 `"SCHEMA"."TABLE"` → Oracle 的 `RENAME TO` 只接受裸表名，REPLACE 换表必报 **ORA-14047**（"ALTER TABLE\|INDEX RENAME 不能与其他分区组合"，措辞误导，实为语法拒绝），临时表接位与备份/回滚三步全部失效 | 默认实现改为「新名只取最后一段」（`lastIdentifier`），Oracle 因此自动继承正确行为；同时把分散在 `PostgreSqlDialect` / `KingbaseDialect` / `Db2Dialect` 的三份 `lastIdentifier` 副本和 `DmDialect#extractTableName`（不识别引号内的点号）全部下沉到 `AbstractDialect`，Pg / Kingbase / 达梦删除各自的重复 override。`DialectTest` 的 rename 断言改用**限定名入参**（原用例只传裸名，正好绕开 `JdbcTableTarget` 的真实调用形态，所以一直没被抓到） | 2026-11-11（Oracle 26ai Free 23.26 实机复现 + 端到端验证，见第五节） |
| D4 | `JdbcViewMigrator#migrate` 的 finally 里 `target.close()` 在前，抛异常时 sourceProvider 永远不关（连接泄漏） | 抽出 `closeQuietly(AutoCloseable)`：每个资源独立 try/catch，关闭失败只记告警，不遮蔽其余资源与 try 块原始异常；新增 `JdbcViewMigratorTest` 断言「前一个 close 抛异常时后续资源仍被关闭」 | 2026-11-23 |
| E12 | `MySqlDialect` / `MariaDbDialect` / `GBase8aDialect` 的 `commentLiteral`、`viewDefinitionQuery` 等逐字重复（B10 需三处同改） | 抽 `MySqlFamilyDialect` 收口家族共性（引用符 / 默认值 / 自增 / 改表名 / 表注释 / 视图查询 / 类型映射表），差异改用 `decimalPrecisionCap()` / `booleanMapping()` / `stringDdlType()` 钩子；MySQL、MariaDB 退化为空子类，GBase 8a 只留 identity 开关与生成列报错 | 2026-11-23 |
| E5 | A 组重构后前提已变：目标内部 `writeFailed` 闩在批次失败时置位、`ISOLATE_AND_CONTINUE` 降级逐行恢复也不清除 → `EtlTask` 判 PARTIAL 以 `close(true)` 显式提交时被闩静默否决，临时表删除、整表迁移作废，而 `EtlResult` 仍报 PARTIAL（提交语义）——双决策点复发且状态说谎。`SKIP_BATCH` 同病（整批丢弃后闩拦截换表，同样静默） | 提交契约收敛两层（评审决策）：①`close(boolean)` 删除闩否决，**无条件服从**调用方 commit——显式传参即唯一决策点；②无参 `close()`（AutoCloseable 隐式入口，调用方从未表达意图）保留保守默认 `close(!writeFailed)`，try-with-resources 异常收尾不会提交半截数据。`JdbcTableTarget` / `XlsxTarget` 同构改造，`Target` 接口 javadoc 固化两层契约；`SKIP_BATCH`+`REPLACE` 经决策维持 PARTIAL 提交（整批丢失由 `failed` 计数与错误摘要感知），README / PUBLIC_API_GUIDE 补组合语义说明。主代码核对：无参 `close()` 在主代码 0 调用（`EtlTask` 恒用 `close(commit)`）。新增回归 `replaceCloseWithExplicitCommitSwapsDespiteEarlierWriteFailure`（H2：写失败置闩 → 好行重写 → 显式 `close(true)`，修复前 `expected:<fine> but was:<keep>` 实证缺陷、修复后换表成功），全量单测无回归 | 2026-11-25（H2 实机复现 + 回归） |

**A 组改动的接口影响（2026-11-21 重构为最终形态）**：`Target` 新增 `default void close(boolean commit)`（默认转发给 `close()`，现有外部实现零改动；`close()` 保持抽象）。提交决策在 `EtlTask` 单点完成：`commit = (状态为 SUCCESS/PARTIAL)`，即「读完整且未崩溃」才以 `close(true)` 提交，`FAILED`/`CANCELLED` 以 `close(false)` 只清理。带两阶段语义的目标（JDBC REPLACE 临时表、CSV/XLSX `.part`）据此决定换表/覆盖或保留原数据；实现内部保留 `writeFailed` 安全闩（自身发生过异常时禁止提交，防止吞异常的调用方误提交部分数据）。不经 `EtlTask` 直接 `prepare → write → close` 的调用方行为与最初一致（`close()` 等价 `close(true)` 即提交）。
**2026-11-25 修订（E5）**：安全闩的否决权从 `close(boolean)` 移除——显式传入的 commit 被无条件服从，不再被内部状态静默否决（吞异常的上层以 PARTIAL 显式提交属合法决策，见归档）；无参 `close()` 作为 AutoCloseable 隐式入口改为保守默认 `close(!writeFailed)`（发生过异常视为不提交），不再是「等价 `close(true)`」，try-with-resources 异常收尾路径因此保留原数据。
**2026-11-25 补充（接口卫生）**：`close(boolean)` 的 `default` 转发实现移除、改为抽象方法——E5 修订后默认转发会把 `close(true)` 路由进保守默认的 `close()`，等于接口自带一个违反「无条件服从」契约的实现样例；库内全部实现（JDBC / XLSX / CSV / Memory 及测试桩）均已显式覆写，外部只实现 `close()` 的自定义 Target 会编译期报错（显式破坏优于静默语义漂移）。

### 已验证非缺陷（实机/静态核验推翻原判断）
| 编号 | 原判断 | 核验结论 | 验证日期 |
|---|---|---|---|
| B6 | 达梦 `SET IDENTITY_INSERT` 可能不接受完整限定名 | **当前实现是对的**。达梦接受完整限定名（带引号 `"SL_B6"."T1"` 与不带引号 `SL_B6.T1` 均可，显式 ID 插入成功）；**反而只给表名 `SET IDENTITY_INSERT T1 ON` 会失败**。原推断方向搞反了 | 2026-10-28 |
| B7 | GBase 8a 可能不支持 `ALTER TABLE ... RENAME TO`，应改用 `RENAME TABLE a TO b` | **当前实现是对的**。GBase 8a 9.5.3 上 `ALTER TABLE sl_rename_src RENAME TO sl_rename_dst` 执行成功，手册里的 `RENAME TABLE a TO b` 同样成功，两种语法都支持，无需改动 | 2026-10-29 |
| C5 | `LogicalTypeCatalog#nullable` 用 `rs.getObject(index, Class)`（JDBC 4.1），担心部分国产库老驱动未实现 → `AbstractMethodError` | **6 个真实驱动全部正常**：SQL Server 13.6、DB2 4.34.30、达梦 8、PostgreSQL 42.2.2、GBase 9.5、Oracle 23.26，`getObject(int, Integer.class/BigDecimal.class/Boolean.class)` 均返回正确值。JDBC 4.1 是 Java 7（2011）就引入的接口方法，仓库自带的驱动全部实现，无法复现。残余风险只在 JDK7 之前编译的驱动上，未做防御性改写（改写反而会引入取值语义差异） | 2026-11-06 |
| D6 | `JdbcDatabaseStatisticsReader` 构造器再包一个 `SingleConnectionDataSource`，与 `JdbcExecutionAdapter` 里的并存 → 「两处各自改 autoCommit 会互相踩」 | **autoCommit 指控不成立（反编译 spring-jdbc 5.3.39 静态核验）**：`SingleConnectionDataSource(Connection, boolean)` 构造路径与 `getConnection()` 均不触碰 autoCommit（`prepareConnection` 只在无参自建连接的 `initConnection()` 里调用，两个包装都只是关闭抑制代理）；真正改 autoCommit 的只有 adapter 侧的 `DataSourceTransactionManager`（batchUpdate 事务内关闭、事务后还原），且 `ConnectionProvider` javadoc 已明确禁止不同任务并发共享 provider。「每次统计调用重复包装 DataSource + 重复读产品名」的浪费属实，不单独立项，并入 E1 统一收敛 | 2026-11-24 |

---

## 五、实机验证记录（2026-10-28）

用本地 Podman 容器实机验证，探针代码见
`src/test/java/io/github/nameof/schemaloom/integration/DialectBehaviorProbeIntegrationTest.java`。

**环境**

| 数据库 | 容器 | 连接 |
|---|---|---|
| DB2 12.1.5.0 | `schema-loom-db2-it` | localhost:15000 / BLUDB / db2inst1 |
| 达梦 DM8 | `schema-loom-dm-it` | localhost:15236 / SYSDBA |

**跑法**（需要容器已启动）

```bash
mvn -o -Pintegration test \
  "-Dtest=DialectBehaviorProbeIntegrationTest#probesDb2IdentifierCase+probesDmIdentifierCase+probesDmIdentityInsert+probesDb2OffsetTimeBinding"
```

**实测结论**

| 项 | 现象 | 结论 |
|---|---|---|
| B5 / DB2 | 建 `"mytable"` 后，`SELECT FROM "MYTABLE"`（修复前方言形态）→ `SQLCODE=-204`；`"mytable"` → OK | BUG 成立，已修 |
| B5 / 达梦 | 字典存 `mytable \| MYTABLE2`；`SL_B5."MYTABLE"` → 对象不存在；`"mytable"` → OK | BUG 成立，已修 |
| B6 / 达梦 | `"SL_B6"."T1"` OK、`SL_B6.T1` OK、`T1`（仅表名）FAIL | **不是 BUG**，原判断推翻 |
| B8 / DB2 | `OFFSET_TIME→TIME`、`OFFSET_TIMESTAMP→TIMESTAMP`，无任何告警 | BUG 成立，已修 |
| C1 / DB2 | `setObject(OffsetTime, 2013)` → `ERRORCODE=-4228`；`setNull(2013)` → 同样报错；降级绑定 `setTime/setTimestamp` → OK | BUG 成立，已修 |
| C1 修复复验 | 走项目 `JdbcValueCodec#write`（带方言）写入 → OK，值 `10.11.12`；null → OK | 修复有效 |

**踩到的环境坑**（下次直接照做，别再排查一遍）

- `podman` 走 SSH 隧道要读 `%USERPROFILE%\.ssh\known_hosts`，沙箱会拦 → 相关命令需放行。
- 容器是 `podman start` 复用的，**不要**重跑 `start-*.ps1`（GBase 重装集群要几分钟）。
- GBase 8a 有 **IP 漂移**问题：实例目录是 `/opt/<节点IP>`，`/home/gbase/.gbase_profile` 里写死了安装时的 IP。
  容器重启后 IP 若漂走，gcware 会连不上（`Could not initialize CRM instance`），
  表现为 `gcluster_services` 报 OK 但实际没有监听。修法是把 `.gbase_profile` 里的旧 IP 换成当前 IP 再重启服务。
- 达梦连接串的库名要填**模式名** `SYSDBA`，填实例名 `DAMENG` 会报「无效的模块名」。
- Git Bash 下跑 `podman exec` 要先 `export MSYS_NO_PATHCONV=1`，否则 `/opt/...` 会被转成 Windows 路径。
- Oracle Free 镜像首次启动要建库（约 10 分钟），日志出现 `ALTER DATABASE OPEN` 后可用。
  脚本建的用户要在 **FREEPDB1** 容器里建，否则连不上：
  `sqlplus -s / as sysdba` 里先 `ALTER SESSION SET CONTAINER=FREEPDB1`。

### 2026-10-29 追加（Oracle 23.26 / 达梦）

| 数据库 | 容器 | 连接 |
|---|---|---|
| Oracle AI Database 26ai Free 23.26.3.0.0 | `schema-loom-oracle-it` | localhost:1521 / FREEPDB1 / SCHEMALOOM |
| 达梦 DM8 | `schema-loom-dm-it` | localhost:15236 / SYSDBA |
| GBase 8a 9.5.3 | `schema-loom-gbase-it` | localhost:15258 / schemaloom_it |

```bash
mvn -o -Pintegration test \
  "-Dtest=DialectBehaviorProbeIntegrationTest#probesOracleBooleanBinding+probesOracleBooleanVariants+probesDmIdentityInsertTargetMismatch"
```

| 项 | 现象 | 结论 |
|---|---|---|
| B9 / 达梦 | 源表 `SRC_A` 有 `IDENTITY`、目标 `TGT_A` 无 → `status=FAILED`，`stage=write`，`JDBC enable identity insert failed` | BUG 成立，已修；修复后 `SUCCESS written=1` |
| B9 反向 / 达梦 | 源表无自增、目标有自增 → 被 `validateAppend` 提前拦下（`nullable source cannot append to NOT NULL column: ID`） | 与 B9 无关，是另一条校验规则，未处理 |
| C2 / Oracle | BOOLEAN 建为 `CHAR(1)`；`setBoolean(true)` → `ORA-12899`（实际 4，最大值 1），`false` → 实际 5 | BUG 成立，已修。**驱动把布尔绑成原生布尔字面量 `TRUE`/`FALSE`** |
| C2 null 路径 | `setNull(Types.BOOLEAN)` → 正常 | 原推断里担心的「类型码 16 无效」在当前 ojdbc 上不成立 |
| C2 变体矩阵 | `CHAR(1)` 写失败 / `VARCHAR2(5)` 存 `TRUE` / `NUMBER(1)` 存 `1` / 原生 `BOOLEAN` 存 `TRUE`；四者 `getObject(Boolean.class)` 读回**全部正常** | 只有写侧有问题；选 `NUMBER(1)` 兼容面最广 |
| C2 修复复验 | DDL 改为 `NUMBER(1)` 后，项目路径写 `true/false/null` → 库内存 `1/0/NULL`，读回 `[true, false, null]` | 修复有效 |
| **B5 回归（自查发现）** | 达梦 REPLACE 报 `JDBC rename replacement target table failed`：临时表建成 `"TGT_A3_tmp"`（`_tmp` 后缀小写），而 `DmDialect#renameTableSql` 仍对新名做「大写后重建」→ 去找 `"TGT_A3_TMP"` → 对象不存在。DB2 的 `renameTableSql` 有同样模式 | **B5 修复引入的回归**，已修：两个方言的 rename 不再改写大小写，与 `quote()` 一致。修复后 DB2 REPLACE 写入 `1-alpha`、达梦 REPLACE/A2/A3 全部 SUCCESS |
| 顺带实证 A3 | 上述失败发生时，`EtlResult.status` 仍是 `SUCCESS`、`written=1`，错误只在 `errors` 里 | 坐实 A3「状态误报」 |
| rename 语法本身 | 手工执行 `ALTER TABLE "SL_B9"."RN0_TMP" RENAME TO "RN0"`、不带引号、新名带引号三种形态**全部成功** | 达梦 rename 语法无问题，纯粹是大小写不一致 |
| B7 / GBase 8a | `ALTER TABLE sl_rename_src RENAME TO sl_rename_dst` → OK；`RENAME TABLE sl_rename_dst TO sl_rename_tmp` → OK；最终只剩 `sl_rename_tmp` | **不是 BUG**，当前实现无需改动 |

---

### 2026-11-06 追加（C3 / C5 / C6）

| 数据库 | 容器 | 连接 |
|---|---|---|
| SQL Server 2022 | `schema-loom-sqlserver-it` | localhost:11433 / master / sa |
| PostgreSQL 16 | `schema-loom-postgres-it` | localhost:15432 / schemaloom_it / schemaloom |
| DB2 12.1.5.0 | `schema-loom-db2-it` | localhost:15000 / BLUDB / db2inst1 |
| 达梦 DM8 | `schema-loom-dm-it` | localhost:15236 / SYSDBA |
| Oracle 23.26 | `schema-loom-oracle-it` | localhost:1521 / FREEPDB1 / SCHEMALOOM |
| GBase 8a 9.5.3 | `schema-loom-gbase-it` | localhost:15258 / schemaloom_it |

```bash
mvn -o -Pintegration test \
  "-Dtest=TypeBindingProbeIntegrationTest#probesFloatTypeMapping+probesPrecisionLoss+probesTypedGetObject+probesTypedGetObjectBoolean+probesSetNullTypeCodes+probesSetNullLogicalCodes+verifiesNullBindingAcrossDatabases"
```

| 项 | 现象 | 结论 |
|---|---|---|
| C3 类型码普查 | SQL Server：`float`→8、`float(24)`/`real`→7；DB2：`FLOAT`→8、`REAL`→7；PG：`float8`→8、`float4`→7；GBase：`FLOAT`→7、`DOUBLE`→8；**达梦：`FLOAT`→6、`REAL`→7、`DOUBLE`→8**；**Oracle：`FLOAT`→6** | 原判断「SQL Server 受害」**不成立**；真正的受害库是达梦与 Oracle，两者的 `FLOAT` 都是双精度却上报 6 |
| C3 精度实测 / 达梦 | `FLOAT` 列存 `1.23456789012345`：`getDouble` 与 `getString` 都是全精度，`getFloat` 得 `1.2345679`；修复前 Schemaloom 读回 `FLOAT32` + `Float 1.2345679` | BUG 成立，已修；修复后读回 `FLOAT64` + `Double 1.23456789012345`，`REAL` 列仍为 `FLOAT32` |
| C5 typed getObject | 6 个驱动 `getObject(int, Integer.class/BigDecimal.class)` 全部返回正确值 | 无法复现，**非缺陷** |
| C5 Boolean 路径 | 6 个驱动 `getObject(int, Boolean.class)` 与 `getBoolean` 全部返回 `true` | 同上 |
| C6 最小复现 | 直接 `setNull` 各类型码：**Oracle 1111 → ORA-17004 无效列类型**；**DB2 2013 → 无法识别 JDBC 类型 -4228**；**PostgreSQL -9 → 无法确定参数类型**；SQL Server / 达梦全部通过 | BUG 成立，已修 |
| C6 标准码接受度 | 逻辑类型全套标准码（16/5/4/-5/3/7/8/12/91/92/93/-2/2013/2014）在 6 个目标库上**只有 DB2 拒绝 2013**，而 OFFSET_TIME 在 DB2 本就降级为 TIME | 证明按逻辑类型取码可行 |
| C6 回归 | 修复前 PG `uuid`(1111) → Oracle 报 `write target batch failed`；SQL Server `nvarchar`(-9) → PostgreSQL 报「无法确定参数类型」。修复后两条并列任务均 `SUCCESS written=2` | 修复有效 |
| E3 回归（自查发现） | PostgreSQL 上 `fromConnectionInfo` 把 database 当 catalog 后，精确表名查询命中 **0** 条（`(null,null,表名)` 命中 1 条，全库 10 条）→ 目标表被判为不存在、重复建表失败 | E3 引入的回归，已修：PG 分支只透传显式 catalog |
| B11 / Oracle | `listTables` 逐模式实测：`%` → 12 张，`SL%` / `SL_FLOAT_PROBE` / `%FLOAT%` 全部 **0** 张（表确实存在，sqlplus 可查） | 新问题，见 B11，未修 |
| C7 / Oracle | `DatabaseMetaData#getColumns` 实测 `BINARY_FLOAT` DATA_TYPE=**100**、`BINARY_DOUBLE`=**101**，SchemaCrawler 同样返回厂商码 | 新问题，见 C7，未修 |

---

### 2026-11-07 追加（C7 / C8 / C9 / B11 / B12）

| 数据库 | 容器 | 连接 |
|---|---|---|
| Oracle 23.26 | `schema-loom-oracle-it` | localhost:1521 / FREEPDB1 / sl_source |
| PostgreSQL 16 | `schema-loom-postgres-it` | localhost:15432 / sl_target / schemaloom |
| 达梦 DM8 | `schema-loom-dm-it` | localhost:15236 / SL_TARGET |

连接信息统一通过环境变量注入（本机 `.workbuddy/it-env.sh`，不进版本库），探针读不到即跳过：

```bash
set -a && . .workbuddy/it-env.sh && set +a
mvn -o -Pintegration test -Dtest='OracleVendorTypeProbeIntegrationTest'
```

| 项 | 现象 | 结论 |
|---|---|---|
| C7 原始码 | 驱动 `getColumns`：`BINARY_FLOAT`=100、`BINARY_DOUBLE`=101；SchemaCrawler 对两者都给 `UNKNOWN(-2147483648)` | 台账原记「厂商码落 default」不准确，实际是 SchemaCrawler 未收录 → UNKNOWN |
| C7 驱动 vs SchemaCrawler | `FLOAT` 列在 `ResultSetMetaData` 里是 `NUMERIC(2) / NUMBER / scale=-127`，在 SchemaCrawler 里是 `FLOAT(6)` | 同一列两套元数据不一致，是 C8 的直接诱因 |
| C7 修复后 | BF→FLOAT32、BD→FLOAT64、FL→FLOAT64、RL→FLOAT64，目标 DDL 均为 `FLOAT` | 修复有效 |
| C7 端到端 | Oracle→PostgreSQL、Oracle→达梦 均 `SUCCESS read=1 written=1`，落地 `BF=1.2345679(Float)`、`BD=1.23456789012345(Double)`、`FL/RL=1.23456789012345(Double)` | 单/双精度各自保住，未互相污染 |
| C8 崩溃 | 未修复前 `listTables` 抛 `IllegalArgumentException: scale must be non-negative`（`USER_INFO.ID`：`size=0 / decimalDigits=-127`），整个 Oracle 元数据读取不可用 | 修复后同表正常输出 `NUMBER(10,0)` |
| B11 模式矩阵 | 修复前：`%`→2 张，`S%` / `%SL%` / `%FLOAT%` / `SL_SOURCE.%` / 精确表名 → 全部 0 张；修复后：`%`→2、`S%`→1、`%FLOAT%`→1、精确表名→1 | 修复有效；`SL_SOURCE.%` 仍为 0 属正常（模式匹配的是表名，不是 schema 限定名） |
| B11 插件实验 | 设 `SC_WITHOUT_DATABASE_PLUGIN=oracle` 禁用插件后，`listTables` 抛 `SchemaLoomException` 且耗时 397 秒 | 插件不能禁用，只能跳过表名下推 |
| C9 原始报错 | `SL_SOURCE.USER_INFO.CREATED_AT`（Oracle 字典 `DATA_TYPE=DATE`，数据为 `2026-01-05 00:00:00` / `2026-02-11 00:00:00`）预览抛 `IllegalArgumentException: logical type DATE requires java.time.LocalDate but received java.time.LocalDateTime` | 两套元数据判定不一致：SchemaCrawler=DATE、驱动 `ResultSetMetaData`=TIMESTAMP |
| C9 修复后 | `JdbcTableSource#preview(2)` 正常返回 2 行，`CREATED_AT` 均为 `LocalDate`（2026-01-05 / 2026-02-11）；反向验证：注释掉 `coerce` 的时间分支，同一用例立即复现原始报错 | 修复有效，新增用例 `previewsOracleDateColumn` 确实抓得住该缺陷 |
| B12 SQL Server | 连 `master` 的会话 `SELECT DB_NAME()` 返回 master，但 `listTables('%')` 只返回 `dbo.user_info`（该表在 `sl_source` 库），master 里 12 张 `sl_*` 表一张未返回；按表名查询全部落空。**改动前基线与改动后行为一致** | 新问题，与本次改动无关，见 B12 |

---

### 2026-11-08 追加（D7 DB2 schema 下推）

容器：`schema-loom-db2-it`（DB2，`localhost:15000 / BLUDB / db2inst1`）。

| 场景 | 实测 |
|---|---|
| 现状（不下推） | `listTables(schema=SL_SOURCE)`：扫描 7 个 schema / 343 张表，**23.6~24.9 秒**，最终只返回 1 张 |
| 下推 `includeSchemas(SL_SOURCE)` | 1 个 schema / 1 张表，**0.64 秒** |
| 下推 `includeSchemas(DB2INST1)` | 1 个 schema / 10 张表，**0.68 秒**（说明下推不是只对某一张表生效） |
| 全库构成 | SYSCAT 200、SYSIBMADM 78、SYSIBM 37、SYSSTAT 14、DB2INST1 10、SYSTOOLS 3、SL_SOURCE 1 |
| 修复后回归 | `Db2SchemaPushdownProbeIntegrationTest`：`listTables` 0.9 秒（含建连）、精确表名查询命中共 6 列、`preview(3)` 正常；`Db2IntegrationTest`（建表/默认值/视图迁移）通过 |
| 日志噪音 | 刷屏来自 `schemacrawler.IndexRetriever` 的 INFO 级日志（每表一行），dbmask `logback.xml` 的 root=info 全放行 → 已在 dbmask 侧加 `schemacrawler` logger=WARN |

---

### 2026-11-21 追加（A 组提交模型重构为 close(boolean commit) 单一决策点）

「close 即提交 + abort() 负向标记」模型（2026-11-14 版）决策分裂两处（EtlTask `incomplete` + 各 Target 内部失败标志），
`JdbcTableTarget#close` 条件为 4 布尔组合，且 `abort()` 先于 `close()` 的时序耦合漏调即静默提交。重构为
`Target#close(boolean commit)`：`close()` 保持抽象并等价 `close(true)`（直接调用方兼容不变），`EtlTask` 以
`commit = (状态为 SUCCESS/PARTIAL)` 单点决策；abort 模型从未提交发布，归档直接以最终形态记录。
PARTIAL 保持提交（维持既有语义；E5 于 2026-11-25 解决归档：闩否决权从 `close(boolean)` 移除，详见第四节）。单元测试（H2 `MODE=MySQL` + 目标桩）：

```bash
mvn -o test -Dtest='EtlTaskTest,JdbcTableTargetContractTest,XlsxBoundaryTest,CsvBoundaryTest'
```

| 场景 | 断言 |
|---|---|
| 正常 REPLACE | `prepare` 后目标与 `_tmp` 并存；`close()` 后只剩目标表，`_tmp` / `_old` 均不残留，数据为本次写入值 |
| `close(false)`（任务失败语义） | 不抛异常，原表数据完好、`_tmp` 已清理；重复 `close()` 幂等不抛 |
| 接位 rename 失败（对含 `_TMP` 的 rename 注入故障） | 由 `close()` 抛出；原表被回滚回原名且数据仍是 `keep`，`_old` 已消失，`_tmp` **被保留**（A1 不再双丢） |
| 源读到一半抛异常 | `close(false)` 被调用，状态 `FAILED` |
| 源声明 5 行只读到 2 行 | `close(false)` 被调用，状态 `FAILED`，`errors[0].stage=read`（`status==SUCCESS` 守卫防止行级失败把截断覆盖回 PARTIAL） |
| 任务中断 | `close(false)` 被调用，状态 `CANCELLED`（单测 `cancelledDoesNotCommit`） |
| `count()` 返回 -1（总数未知） | 跳过读少检查，`close(true)` 提交（单测 `unknownTotalStillCommits`） |
| write 失败（FAIL_FAST） | `close(false)` 被调用，状态 `FAILED`（单测 `writeFailureFailFastDoesNotCommit`） |
| PARTIAL（行级失败未崩溃） | `close(true)` 保持提交（单测 `partialStatusStillCommits`，语义钉死防误改） |
| `close(true)` 抛异常 | 状态由 `SUCCESS`/`PARTIAL` 降为 `FAILED`；`close(false)` 清理失败不降级（不遮蔽 CANCELLED） |
| XLSX / CSV `close(false)` | 正式文件字节与提交前完全一致，`.part` 保留 |

### 2026-11-14 追加（A1~A4 提交模型回退为 close 即提交）

提交模型从「finish() 显式提交」回退为「close 即提交 + abort() 负向标记」，状态机从三态退化为单布尔（2026-11-21 已再次重构为 `close(boolean commit)`，见上节）。单元测试（H2 `MODE=MySQL` + 目标桩）：

```bash
mvn -o test -Dtest='EtlTaskTest,JdbcTableTargetContractTest,XlsxBoundaryTest,CsvBoundaryTest'
```

| 场景 | 断言 |
|---|---|
| 正常 REPLACE | `prepare` 后目标与 `_tmp` 并存；`close()` 后只剩目标表，`_tmp` / `_old` 均不残留，数据为本次写入值 |
| `abort()` 后 `close` | 不抛异常，原表数据完好、`_tmp` 已清理 |
| 接位 rename 失败（对含 `_TMP` 的 rename 注入故障） | 由 `close()` 抛出；原表被回滚回原名且数据仍是 `keep`，`_old` 已消失，`_tmp` **被保留**（A1 不再双丢） |
| 源读到一半抛异常 | `abort()` 被调用，状态 `FAILED` |
| 源声明 5 行只读到 2 行 | `abort()` 被调用，状态 `FAILED`，`errors[0].stage=read` |
| `close()` 抛异常 | 状态由 `SUCCESS` 降为 `FAILED`，`errors[0].stage=close-target` |
| XLSX / CSV 被 `abort` | 正式文件字节与提交前完全一致，`.part` 保留 |

真实容器（`set -a && . .workbuddy/it-env.sh && set +a`）：

```bash
mvn -o -Pintegration test -Dtest='Db2IntegrationTest,DmIntegrationTest,PostgreSqlIntegrationTest'
```

| 数据库 | 结果 |
|---|---|
| DB2 12.1.5 | `Db2IntegrationTest` 通过（含 REPLACE / 默认值 / 视图迁移） |
| 达梦 DM8 | `DmIntegrationTest` 通过（含 REPLACE / IDENTITY / 视图） |
| PostgreSQL 16 | `PostgreSqlIntegrationTest` 2 项通过（含 REPLACE） |
| 三库连续跑两遍 | 第二遍仍全绿 → 换表后 `_old` 备份确实被删除，没有残留（残留会让第二遍提交报「备份表已存在」） |
| Oracle 23.26 | 未验证：`ORA-01017 invalid credential`，本机 `.workbuddy/it-env.sh` 的 Oracle 凭据与容器现状不一致（环境问题，非代码）。**2026-11-11 补验**：改用 `SCHEMALOOM_IT_ORACLE_USER=sl_target` + 端口 1521 跑通 REPLACE（含 B13 修复），见本节 2026-11-11 追加 |
| SQL Server 2022 | 未验证：既有 B12 导致 `table not found`；已用 `git stash` 回到基线复跑，**同样 2 项报同样的错**，确认与本次改动无关 |
| MariaDB / GBase | 环境变量缺失，测试跳过 |

### 2026-11-11 追加（B13 / Oracle REPLACE rename）

现场：Oracle 作为目标库、`TargetMode.REPLACE` 在 `stage=commit-target` 失败，`ALTER TABLE "SL_TARGET"."USER_INFO_tmp" RENAME TO "SL_TARGET"."USER_INFO"` 报 `ORA-14047`。

| 验证项 | 命令 / 方式 | 结果 |
|---|---|---|
| 语法定性 | `schema-loom-oracle-it`（26ai Free 23.26）内 sqlplus：`alter table "SL_SOURCE"."ZZ_RN_TMP" rename to "SL_SOURCE"."ZZ_RN"` vs `... rename to "ZZ_RN"` | 前者 **ORA-14047**，后者 `Table altered` → 根因确认为「新名不能带 schema 限定」，与分区无关 |
| 正向端到端 | `OracleEtlIntegrationTest#replacesTargetTableThroughQualifiedRename`（`SCHEMALOOM_IT_ORACLE_USER=sl_target`，schema `SL_TARGET`，端口 1521） | `SUCCESS`。分支一（目标表不存在）2 行、分支二（目标表已存在，走备份→接位→删备份）整体替换为 1 行；`_tmp` / `_old` 均无残留 |
| 反向验证 | 把 `AbstractDialect#renameTableSql` 临时回退成拼接全限定名，重跑同一用例 | 报 `commit-target: JDBC rename replacement target table failed`，与线上现场完全一致 → 用例确实抓得住该缺陷 |
| 达梦 / DB2 回归 | `DmIntegrationTest`、`Db2IntegrationTest` | 均通过（两者改用了下沉后的 `lastIdentifier`，rename 输出与改动前一致） |
| 单测 | `mvn test` | 142 项通过，1 项跳过；`DialectTest` 新增 `stripsOnlyTheTrailingIdentifierAndIgnoresDotsInsideQuotes` |
| 现场残留 | `SL_TARGET.USER_INFO_tmp`（3 行，失败时按 `settleReplace` 保留） | **未动**，需人工确认后 `DROP TABLE "SL_TARGET"."USER_INFO_tmp"` 或直接改名为正式表 |

### 2026-11-24 追加（D1 元数据探测路径，7 库）

容器全部为本地 Podman（连接信息走 `.workbuddy/it-env.sh` 环境变量，口令不进源码）：

| 数据库 | 版本 | 驱动 |
|---|---|---|
| PostgreSQL 16 | 16.15 | postgresql-42.2.2.jar |
| MariaDB 11.4 | 11.4.13 | mariadb-java-client-3.5.10.jar |
| SQL Server 2022 | 16.00.4295 | mssql-jdbc-13.6.0.jre8.jar |
| DB2 12.1.5.0 | SQL120150 | db2jcc4.jar 4.34.30 |
| 达梦 DM8 | 8.1.4.170 | DmJdbcDriver8.jar |
| Oracle 26ai Free | 23.26.3.0.0 | ojdbc8-23.26.3.0.0.jar |
| GBase 8a | 9.5.3.28 | gbase-connector-java-9.5.0.10.jar |

探针为临时工具 `.workbuddy/tmp/D1Probe.java`（不进版本库），对每库 8~9 类 SQL
（基础列 / 别名+表达式 / 内层 ORDER BY / 参数化 / 聚合 / CTE / 重复列名 / `SELECT *` / Oracle 厂商类型）
逐字段比对 `columnLabel`、`columnName`、`columnType`、`typeName`、`precision`、`scale`、`nullable`、`displaySize`。
四条路径：A=执行原 SQL 取 `rs.getMetaData()`（现状基准）、B=`ps.getMetaData()`、
C=`SELECT * FROM (<sql>) SL_D1_WRAP WHERE 1=0`、D=`setMaxRows(1)` + 执行原 SQL。

| 项 | 实测结论 |
|---|---|
| **台账原修法一 `getMetaData()` 语义一致性** | 56 组中 **54 组与 A 逐字段一致**，含 Oracle `BINARY_FLOAT`/`BINARY_DOUBLE` 厂商码、`NUMBER` 的 -127 scale、DATE→TIMESTAMP（C7/C8/C9 老大难）；参数绑定与否结果相同。**缺口 2 个且都在 CTE**：SQL Server 抛 319（驱动内部改写 SQL 使 `WITH` 不再是语句开头）、GBase 8a 返回 null |
| 该修法的规范依据 | JDBC 4.1 §6.3 把 `PreparedStatement#getMetaData` 列为 **optional method**；Javadoc 明确允许返回 null、未实现须抛 `SQLFeatureNotSupportedException`，并警告部分驱动内部仍会执行（pgjdbc 走 `LIMIT 0`）。**「不执行且完全一致」不是契约**，不能无条件替换 |
| **台账原修法二 包一层 `WHERE 1=0` 方言兼容性** | 语法层：派生表别名 MySQL/MariaDB/GBase/SQL Server 强制（`Every derived table must have its own alias` / 1064 / 156），Oracle/DB2/达梦/PG 可省；别名以下划线开头在 DB2（-20521）/Oracle（ORA-00911）非法；内层 `ORDER BY` 被 SQL Server 禁（1033）；重复列名在 5 库失败（8156 / 1060 / -2112 / ORA-00918）；尾部分号 7 库全挂；内层 CTE 被 SQL Server 拒（156） |
| 包装法的**静默元数据漂移** | 更危险：达梦 `isNullable` 由 0→1（会让目标表丢 `NOT NULL`）、Oracle 聚合列 scale 0→-127（重踩 C8）、MariaDB/GBase `getColumnName` 由底层列名变别名。语法错还能发现，这类不报错的漂移会静默改写 Schema |
| **选定修法 D `setMaxRows(1)`** | 7 库全部用例与 A **逐字段一致**，零方言、零语义风险；`read()` / `preview()` 的行数语义未变（单测锁定） |
| 收益实测 | 16384 行大表，只取 metadata 不 `next()`：A 7~17ms → D 0~2ms（GBase 14→0、SQL Server 8~10→1~2、PG 7~9→0~1、MariaDB 7→0 最明显）。MySQL 系「`executeQuery()` 即全量拉内存」的开销确实被挡住 |
| **调用顺序陷阱** | `setFetchSize(1000)` 必须**先于** `setMaxRows(1)`。反序（先 maxRows 再 fetchSize）在 DB2（-4461）、达梦（HY024/6036）、GBase（`Illegal value for setFetchSize()`）配置阶段直接抛异常；按项目真实顺序 7 库全通过，`getMaxRows()` 均为 1。`JdbcExecutionAdapter#statement()` 现有顺序正确，未改动 |
| 回归 | `mvn -o test` 156 项通过、1 跳过（G1 的 `@Ignore`）；新增 `JdbcQuerySourceInspectTest` 3 项 |

**结论与决策**：台账原列的两条建议修法**均不可直接采用**——B 需兜底且受驱动能力约束，C 有方言矩阵与静默漂移。
按评审决策采用最小改动 `setMaxRows(1)`，D1 归「部分解决」：服务端仍执行完整查询计划，
根治留待 E10（权威 Schema 注入）/ `getMetaData()` 分层方案，不在此条堆叠补丁。
探针输出：`.workbuddy/tmp/d1-probe2.out`、`d1-order.out`、`d1-maxrows2.out`。

---

## 六、需实机验证的条目

以下条目的结论来自语法规范推导，**没有在真实数据库上复现过**。动手改之前先确认，避免改了不存在的 BUG。

| 编号 | 待验证点 | 怎么验 |
|---|---|---|
| B1 | MySQL/MariaDB 上 SchemaCrawler 是否把主键索引暴露到 `table.getIndexes()`。**H2 上实测不会**（SchemaCrawler 自行排除）→ 那么 B1 描述的「必报 `CREATE INDEX PRIMARY`」可能压根不可复现 | 找台 MySQL，读 `TableInfo#getIndexes()` 看有没有名为 PRIMARY 的行 |
| B10 | MySQL 系真实库上，注释里的反斜杠转义是否如预期 | MariaDB 容器已就绪（`schema-loom-mariadb-it`，localhost:13306，root/Root_IT_2026），建一个注释含 `\` 的表跑迁移即可 |
| A 组回归 | 提交流程（close 即提交 + `<表>_old` 备份换表）在**尚未跑通**的方言上的 rename 行为：Oracle 30 字符标识符上限（表名 + `_old` / `_tmp` 可能超长）、SQL Server `sp_rename`（三段名丢库段）、MariaDB / GBase | DB2 / 达梦 / PostgreSQL / Oracle（2026-11-11，B13）已验证通过（见第五节）。2026-11-14 回退为 close 即提交后，DB2 / 达梦 / PostgreSQL 复验连续两遍通过、`_old` 无残留。剩余：SQL Server（需先修 B12）、MariaDB / GBase |
| B14 | KingbaseES 上 `includeTables` 下推是否真的会像 Oracle 那样被插件误解析（决定 B14 是「已发生」还是「潜在」） | 起 KingbaseES 容器，建 `user_info` 后跑 `listTables`：`%` / `S%` / 精确表名 / `%USER%` 四种模式对比条数；再设 `SC_WITHOUT_DATABASE_PLUGIN=postgresql` 复跑一次 |
| B15 | 加引号的小写视图名能否被 `viewDefinitionQuery` 查到（三个方言各一次） | DB2：`CREATE VIEW "v_lower" AS SELECT 1 AS c FROM SYSIBM.SYSDUMMY1`，再走 `JdbcViewMigrator#migrate("\"v_lower\"", ...)`，看是否报 `view definition is unavailable`；达梦 / Oracle 同理（Oracle 用 `"v_lower"`） |
| C10 | 19c 及更早的 ojdbc 上，对 `NUMBER(1)` 列执行 `setNull(Types.BOOLEAN=16)` / `setBoolean` 是否被拒 | 起 Oracle 19c 容器，`CREATE TABLE t(b NUMBER(1))`，分别跑 `ps.setNull(1,16)`、`ps.setBoolean(1,true)`、`ps.setNull(1,4)` 三组，记录 ORA 码 |
| E10 | `align` 的位置对齐是否能被真实场景触发（决定它是「潜在」还是「已有 BUG」） | 构造列顺序与 SchemaCrawler 不一致的对象：视图里 `SELECT b, a FROM t` 让驱动顺序 ≠ DDL 顺序；或临时改 `listTables` 的列排序后跑 `JdbcTableSource#read`，看是否整列错位且无异常 |

---

## 七、附带说明

- **JDBC 驱动适配的设计边界（2026-11-24 决策）**：项目只在 **API 调用边界**保证行为（语句释放、参数绑定、
  事务边界、异常转换），**不深入定制各 JDBC 驱动的私有逻辑**（如 MySQL 流式读取的 `Integer.MIN_VALUE`、
  驱动内部 SQL 改写规避等）。驱动特定的行为差异由调用方通过连接参数自行配置。
  背景：原 D2 条目（MySQL Connector/J 默认无视 `setFetchSize`，需 URL 带 `useCursorFetch=true`
  或 `setFetchSize(Integer.MIN_VALUE)` 才生效，否则大结果集全量进内存）因此撤销立项，不作为待办跟踪；
  该知识点保留在此，调用方遇到 MySQL 大表 OOM 时可按上述参数自行规避。
- **主键索引过滤的单一落点**：`DatabaseMetadataService#indexes` 是全局唯一过滤点，方言层不再重复实现
  （含「保留名 PRIMARY」与「列集合匹配主键」两项判据）。新增过滤逻辑请放这里，不要放进方言。
- **默认跑单测时排除集成测试**：`mvn test` 已排除 `**/*IntegrationTest.java` 与 `**/*PerformanceCheck.java`。
  新增依赖外部数据库的测试必须落到 `integration` 包并以此后缀命名，否则本机没库时 `mvn test` 会挂假红灯
  （原 G1 条目的教训；G 组已于 2026-11-24 整体移除，教训保留在此）。

---

## 八、2026-11-21 审查合并记录（`8ad61b9f..HEAD` 方言补丁集）

来源：外部审查清单《代码审查计划：8ad61b9f → HEAD 的多库方言补丁集》，含 7 条正确性、8 条设计耦合、8 条卫生项，共 23 条。
**逐条对照代码核验后合并，不照抄**：成立 17 条、部分成立 4 条（其中 B14 / C10 结论待实机验证）、
重复既有条目 1 条、不成立 1 条（`dropTemporaryIfPresent`，见下表）。
行号已按当前代码重新核对（清单基于 `3b18b2f`，当前 HEAD 为 `91f1eea` 且工作区含 A1~A4 未提交改动，
例如 Db2 视图大写从清单写的 `62/65` 实际位于 `46-50`）。

| 审查编号 | 台账落点 | 核验结论 |
|---|---|---|
| R1 `align` 按位置对齐 | **E10** | **成立**（缺口真实存在）；但「整列错位」的触发场景未构造出来 —— `SELECT *` 保证驱动列序与 DDL 列序一致，列为待验证 |
| R2 两套表名 LIKE→正则 | **并入 E6** | **重复**：E6 已登记同一处（第 72 行未 `Pattern.quote`），本次仅补充 `user_info` 下划线语义与「下推/本地不是同一批表」两条后果 |
| R3 下推黑白名单相反 | **B14**（新） | **部分成立**：策略相反、Kingbase 被放行为实证；Kingbase 上的实际后果未验证 → 标存疑 |
| R4 DB2/达梦视图大写 | **B15**（新） | **成立**（推断级）；顺带核实 **Oracle 同病**（`OracleDialect:42-43`），清单只提了两个方言 |
| R5 Oracle BOOLEAN 绑定类型码 | **C10**（新） | **部分成立**：结构缺口（`DatabaseTypeMapping` 无法表达 DDL 与绑定分离）确实存在；但「23ai 前不保证」与 C6 实测（Oracle 接受 16）不冲突也不互证，标存疑 |
| R6 `coerce` 静默有损 | **C11**（新）+ C9 遗留 | **部分成立（其中 DATE 部分与 C9 重复）**：`DATE` 截断已在 C9「遗留取舍」登记；`BigDecimal→Double` **不算缺陷**（FLOAT64 列本身就是双精度），真正成立的是「收敛过程无日志」 |
| R7 依赖 `Integer.MIN_VALUE` | **并入 E9** | **成立**（低危）：同一类的「SchemaCrawler 内部约定耦合」，并入 E9 的拆分类条目，不单列 |
| D1 方言能力三处分裂 | **E8**（新） | **成立** |
| D2 486 行七类职责 | **E9**（新） | **成立** |
| D3 双元数据源 | **E10**（新） | **成立**（与 R1 同根，合并为一条，避免同一问题两个编号） |
| D4 连接配置承担大小写规范化 | **E11**（新） | **成立** |
| D5 `commentLiteral` 三份复制 | **E12**（新） | **成立**，且 `viewDefinitionQuery` 也是三份逐字相同（清单未提） |
| D6 `segments()` 手写切分 | **并入 E11** | **部分成立**：引号内点号确实被破坏；「必须用 Hutool」需先确认 Hutool `split` 能否保留空段，保留不了则手写是功能需要 |
| D7 构造器冗余 + 返回值丢弃 | **并入 E13** | **成立**（小清理） |
| D8 单次调用取 3+1 次产品名 | **原 D9**（2026-11-24 并入 E8） | **成立** |
| 卫生：`join()` 死代码 | **E13** | **成立**（全项目零调用） |
| 卫生：恒等三元分支 | **E13** | **成立**（`:225` 后两个分支相同） |
| 卫生：`dropTemporaryIfPresent` 查 tmp 删 `writeTable` | 不落条目 | **不成立（已消解）**：HEAD 版本确实存在「查 `temporaryTable()`、删 `writeTable`」的两处来源，但唯一调用点在 `replaceMode` 分支内、两者本就是同一对象，无实际后果；工作区 A1 改动已统一为 `dropIfPresent(writeTable, ...)`（查与删用同一入参） |
| 卫生：`excelTime` 每次编译正则 | **并入 E13**（原 D9 已撤销单独立项） | **成立**（`excelDate` 同样问题） |
| 卫生：`MetadataQuery` 缺 `equals/hashCode` | **E14** | **成立** |
| 卫生：`getTable` 把 `tablePattern` 当表名 | **E14** | **成立**（同一个参数两种语义） |
| 卫生：新集成测试 | **原 G3**（2026-11-24 随 G 组整体移除，未立项） | **成立**，且比清单更严重：`TypeBindingProbe` 里是 **8 个库的真实口令**，会随提交进版本库 |
| 卫生：台账统计不自洽 | 本次已修正 | **成立**：原「合计 46」与「15+1+28=44」不符 —— B 组小计含 B6/B7 却没算进任何一列，C 组「已解决 9」与归档 8 行不符（多算了 C5）。已在第一节拆出「已验证非缺陷」列并按归档逐行重算 |

**未采纳的建议**：清单要求「不在报告里给定论式重构方案」。本台账对 E8~E14 只登记「问题 + 方向 + 代价」，
具体切分方式（例如 `DialectCapabilities` 用字段还是独立类）留待确认后再定，避免先改结构再返工。
