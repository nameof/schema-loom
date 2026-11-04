# 问题台账（Issue Tracker）

代码审查发现的缺陷与优化项跟踪表。

**更新规则**：每解决一条，把该条从「待办」移到文末「已解决归档」并标注解决日期；部分解决的留在
「部分解决」区并写明残余问题。待办区里剩下的，一律是还没动的。

**最后更新**：2026-11-03

---

## 一、进度总览

| 分组 | 待办 | 部分解决 | 已解决 | 小计 |
|---|---|---|---|---|
| A 数据丢失 / 状态误报 | 4 | 0 | 0 | 4 |
| B 各数据库特有分支 | 0 | 1 | 7 | 10（含 B6 / B7 已验证非缺陷） |
| C 类型系统与编解码 | 4 | 0 | 2 | 6 |
| D 性能与资源 | 6 | 0 | 0 | 6 |
| E 设计与耦合 | 5 | 0 | 1 | 6 |
| F 元数据与文档不一致 | 2 | 0 | 2 | 4 |
| G 测试与工具（新增） | 2 | 0 | 0 | 2 |
| **合计** | **23** | **1** | **12** | **38** |

**建议处理顺序**

1. **A 组**（会真丢数据，无论概率高低都不可接受；A3 已被实机坐实）
2. **C3-C6 / G2**（正确性）
3. **D / E / F**（性能、重构、文档一致性，可批量做）
5. **D / E / F**（性能、重构、文档一致性，可批量做）

**依据列含义**

- `推断`：从数据库语法规范或代码逻辑推导，**未经实机验证**
- `实证`：已在真实数据库容器或单元测试中验证
- `存疑`：结论本身待确认，可能不是真 BUG

> 2026-10-28 用本地 Podman 容器（DB2、达梦）实机验证了 B5 / B6 / C1 / B8，
> 详见第五节「实机验证记录」。其中 **B6 被实机推翻——不是 BUG**。

---

## 二、待办清单

### A 组：会真正丢数据 / 状态误报

| 编号 | 位置 | 问题与后果 | 建议修法 | 依据 |
|---|---|---|---|---|
| A1 | `JdbcTableTarget#close` | 先 DROP 原表再 RENAME 临时表；catch 里又调 `dropTemporaryIfPresent()`。rename 一旦失败 → 原表已删 + 临时表被删 = **双丢，不可恢复** | catch 里不再删临时表；或改为「先 rename 原表为 `_old`，成功后延后删除」 | 推断 |
| A2 | `EtlTask#run` / `JdbcTableTarget` | `targetWriteFailed` **只在 `write()` 里置位**。source 读异常、被 `Future.cancel` 中断时仍为 false → 只读了一半数据，close 照样执行 rename，**半份数据替换全表** | 失败标记扩展到读阶段与中断路径；close 前校验实际写入状态 | 推断 |
| A3 | `EtlTask#run` finally | `target.close()` 抛异常只 `addError`，不改 `status` → 返回 `SUCCESS` 但表根本没换，`getStatus()` 骗人 | close 异常时置 `status=FAILED` | **实证**：2026-10-29 达梦 REPLACE 中 rename 失败，返回 `status=SUCCESS`、`written=1`，错误只出现在 `errors` 的 `stage=close-target` 里，调用方按 status 判断会认为迁移成功 |
| A4 | `XlsxTarget#close` | 与 A2 同构：`failed` 只在 `write()` 置位 → 读阶段失败也会把 `.part` 覆盖正式 xlsx（CsvTarget 做对了，Xlsx 没有） | 与 CsvTarget 对齐，读阶段失败同样不覆盖正式文件 | 推断 |

### B 组：各数据库特有分支缺失 / 写错

**本组已全部处理完**（7 项已修 + B6 / B7 实机验证为非缺陷 + B3 部分解决）。明细见第四节归档与「已验证非缺陷」区。

### C 组：类型系统与编解码

| 编号 | 位置 | 问题与后果 | 建议修法 | 依据 |
|---|---|---|---|---|
| C3 | `JdbcTypes#logical` | `Types.FLOAT → FLOAT32`。**SQL Server 的 `FLOAT` 是 53 位双精度**，被降级成单精度 → 精度丢失 | FLOAT 按目标方言区分；SQL Server 应映射为 FLOAT64 | 推断 |
| C4 | `LogicalTypeCatalog#time` | Excel decode 只写 `LocalTime.parse(String.valueOf(raw))`。日期列有 `excelDate()` 兜底，**时间列没有** → Excel 时间单元格（序列号 0.5）必然解析失败 | 时间列补序列号兜底，与日期列对称 | 推断 |
| C5 | `LogicalTypeCatalog#nullable` | 用 `rs.getObject(index, Class)`（JDBC 4.1）。部分国产库老驱动未实现 → `AbstractMethodError` | 改用 `getObject(int)` + 本地转换，或捕获 `AbstractMethodError` 后降级 | 推断 |
| C6 | `JdbcTableTarget#setValue` | null 值用**源列**的 `jdbcType` 调 `setNull`。跨库时源类型码目标库可能不支持 | null 绑定改用目标类型，或对不支持的类型码降级为 `setNull(Types.NULL)` | 推断 |

### D 组：性能与资源

| 编号 | 位置 | 问题与后果 | 建议修法 | 依据 |
|---|---|---|---|---|
| D1 | `JdbcQuerySource#recordSchema` | 为拿 `ResultSetMetaData`，把**整条 SQL 真跑一遍且不设 maxRows**。`SELECT * FROM 大表` 会全量执行两次（这里一次 + read 一次） | 改用 `PreparedStatement#getMetaData()`（不执行）；或包一层 `SELECT * FROM (...) WHERE 1=0` | 推断 |
| D2 | `JdbcExecutionAdapter#statement` | 只调 `setFetchSize(1000)`。MySQL Connector/J 不开 `useCursorFetch` 或不传 `Integer.MIN_VALUE` 时**该参数无效** → 整个结果集进内存，大表 OOM | MySQL 用 `setFetchSize(Integer.MIN_VALUE)`，或要求 URL 带 `useCursorFetch=true` | 推断 |
| D3 | `XlsxSource` | `recordSchema()` 开一次工作簿，`read()` 又开一次 → 大文件双倍内存 + 双倍解析 | 复用工作簿，或改流式读取（XSSF eventmodel） | 推断 |
| D4 | `JdbcViewMigrator#migrate` finally | `target.close()` 在前，若抛异常 → **sourceProvider 永远不关**，连接泄漏 | finally 里各自 try/catch，保证互不遮蔽 | 推断 |
| D5 | `JdbcDriverLoader#TcclInvocationHandler` | 每次 JDBC 调用都要切 TCCL + 反射 invoke，且 ResultSet 被递归代理 → 百万行场景开销明显 | 缩小代理范围到连接级别，或按驱动白名单决定是否代理 | 推断 |
| D6 | `JdbcDatabaseStatisticsReader` 构造器 | 又 `new` 一个 `SingleConnectionDataSource` 包同一个连接，与 `JdbcExecutionAdapter` 里的并存 → 两处各自改 autoCommit 会互相踩 | 复用同一个 DataSource，避免重复包装 | 推断 |

### E 组：设计与耦合

| 编号 | 位置 | 问题与后果 | 建议修法 | 依据 |
|---|---|---|---|---|
| E1 | 全局 | `loader → provider → connection → source/target → task` 五层套娃（README 自己的「优化 TODO」第一条）。同一连接被 `SingleConnectionDataSource` 包了 2-3 次 | 合并 provider/connection 层，DataSource 只包装一次 | 推断 |
| E2 | `DialectRegistry` 构造 | 硬编码 `new MySqlDialect()` 等 9 个方言，**没有 SPI / 注册入口** → 第三方加方言只能改源码 | 改 SPI（`META-INF/services`）+ 保留内置默认 | 推断 |
| E3 | `MetadataQuery#fromConnectionInfo` | 里面写了 MySQL/MariaDB/GBase/DB2/Kingbase 各自的 catalog-schema 语义，**但主流程（`JdbcTableSource`/`JdbcTableTarget`）根本没调它**，直接 `new MetadataQuery(...)`。两套语义并存，DB2「schema 回退到数据库名」在主路径上不生效 | 主流程统一走 `fromConnectionInfo`，或删除死代码 | 推断 |
| E5 | `EtlTask` + REPLACE 模式 | `ISOLATE_AND_CONTINUE` 降级逐行后，只要有 1 行坏数据 → `write()` 抛错 → `targetWriteFailed=true` → close 删临时表 → **整表迁移作废**。两个特性语义上互斥，当前没有提示 | REPLACE 模式下禁用 `ISOLATE_AND_CONTINUE`，或文档明示互斥 | 推断 |
| E6 | `DatabaseMetadataService#listTables` | 表名转正则用 `replace("%",".*").replace("_",".")`，**没有 `Pattern.quote`**；同文件 `sqlPattern()` 却做了 quote → 两套标准，表名含 `.` `(` 时误匹配或 `PatternSyntaxException` | 用 `Pattern.quote` 转义字面部分 | 推断 |

### F 组：元数据与文档不一致

| 编号 | 位置 | 问题与后果 | 建议修法 | 依据 |
|---|---|---|---|---|
| F1 | `DatabaseMetadataService#catalog` | `query.getCatalog()==null` 时 **schemaPattern 直接为 null，不下推**。Oracle/DB2 场景 catalog 通常为空 → 全库扫元数据（慢且大库易超时），靠下游 `matches()` 硬过滤 | catalog 为空时按方言把 database 作为 catalogPattern 下推 | 推断 |
| F3 | README catalog/schema 表 | 说「DB2 / DM 未指定 Schema 时回退到数据库名」，但 `DatabaseConnectionInfo` 只对 `POSTGRESQL`/`KINGBASE` 默认 `public`，DB2/DM 没实现 | 要么实现回退，要么改文档 | 实证 |

### G 组：测试与工具（新增）

| 编号 | 位置 | 问题与后果 | 建议修法 | 依据 |
|---|---|---|---|---|
| G1 | `EtlTaskTest#localTest` | 连 `localhost:3306` 真实 MySQL（库 `hxl`/`hxl2`）。类名是 `EtlTaskTest`，**逃过了 surefire 的 `**/*IntegrationTest.java` 排除规则** → 本机没 MySQL 时 `mvn test` 永远挂一个假红灯，掩盖真实失败 | 改名为 `*IntegrationTest` 或加 `@Ignore` / `Assume` | 实证 |
| G2 | 各 `Dialect#mappings` 的 DECIMAL 项 | 只分别 clamp 了 precision 和 scale，**没有约束 `scale <= precision`** → 仍可生成 `DECIMAL(5,30)` 这类非法 DDL | `scale = min(scale, precision)` | 推断 |

---

## 三、部分解决

| 编号 | 位置 | 已修部分 | 残余问题 | 依据 |
|---|---|---|---|---|
| B3 | `SqlServerDialect#commentSql` | 已修：①`@level0name` 不再硬编码 `dbo`，从目标限定名解析架构；②REPLACE 模式下注释落到 `<表>_tmp` 而不是源表名（原本会加错对象或丢失） | 回退链第 2 步用的是**源表** schema。源/目标 schema 不同时仍会加错。彻底解法是给 `commentSql` 传目标连接信息 —— 那是 `DatabaseDialect` 接口级改动，波及 9 个方言，未做 | 推断 |

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

### 已验证非缺陷（实机推翻原判断）

| 编号 | 原判断 | 实机结论 | 验证日期 |
|---|---|---|---|
| B6 | 达梦 `SET IDENTITY_INSERT` 可能不接受完整限定名 | **当前实现是对的**。达梦接受完整限定名（带引号 `"SL_B6"."T1"` 与不带引号 `SL_B6.T1` 均可，显式 ID 插入成功）；**反而只给表名 `SET IDENTITY_INSERT T1 ON` 会失败**。原推断方向搞反了 | 2026-10-28 |
| B7 | GBase 8a 可能不支持 `ALTER TABLE ... RENAME TO`，应改用 `RENAME TABLE a TO b` | **当前实现是对的**。GBase 8a 9.5.3 上 `ALTER TABLE sl_rename_src RENAME TO sl_rename_dst` 执行成功，手册里的 `RENAME TABLE a TO b` 同样成功，两种语法都支持，无需改动 | 2026-10-29 |

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

## 六、需实机验证的条目

以下条目的结论来自语法规范推导，**没有在真实数据库上复现过**。动手改之前先确认，避免改了不存在的 BUG。

| 编号 | 待验证点 | 怎么验 |
|---|---|---|
| B1 | MySQL/MariaDB 上 SchemaCrawler 是否把主键索引暴露到 `table.getIndexes()`。**H2 上实测不会**（SchemaCrawler 自行排除）→ 那么 B1 描述的「必报 `CREATE INDEX PRIMARY`」可能压根不可复现 | 找台 MySQL，读 `TableInfo#getIndexes()` 看有没有名为 PRIMARY 的行 |
| B10 | MySQL 系真实库上，注释里的反斜杠转义是否如预期 | MariaDB 容器已就绪（`schema-loom-mariadb-it`，localhost:13306，root/Root_IT_2026），建一个注释含 `\` 的表跑迁移即可 |

---

## 七、附带说明

- **主键索引过滤的单一落点**：`DatabaseMetadataService#indexes` 是全局唯一过滤点，方言层不再重复实现
  （含「保留名 PRIMARY」与「列集合匹配主键」两项判据）。新增过滤逻辑请放这里，不要放进方言。
- **默认跑单测时排除集成测试**：`mvn test` 已排除 `**/*IntegrationTest.java` 与 `**/*PerformanceCheck.java`。
  新增依赖外部数据库的测试必须落到 `integration` 包并以此后缀命名，否则会变成 G1 那样的假红灯。
