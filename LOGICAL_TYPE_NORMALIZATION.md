# LogicalType 数据归一化设计

## 30 秒结论

```text
当前：Source 把各种原始 Java 对象直接放进 DataRecord，Target 再自行猜测如何写入。
问题：LogicalType 相同，不代表实际 Java 类型相同，跨格式和异库时会失败。
方案：Source 负责“原始值 -> 标准值”，Target 负责“标准值 -> 目标值”。
保证：DataRecord 只允许 LogicalType 对应的一种标准 Java 类型；不能无损转换就提前报错。
```

| 改造位置 | 核心动作 |
|---|---|
| Source | 增加 CSV、XLSX、JDBC 各自的 Decoder |
| `DataRecord` | 按 `LogicalType` 校验实际 Java 类型 |
| Target | 增加 CSV、XLSX、JDBC 各自的 Encoder |
| 数据库方言 | 声明目标库支持的类型，写入前检查 |

## 1. 背景

SchemaLoom 已经使用 `LogicalType` 描述字段语义，例如 `DATE`、`TIMESTAMP` 和 `DECIMAL`。但当前 `LogicalType` 只约束 Schema，没有约束 `DataRecord` 中实际保存的 Java 对象。

例如，同一个 `LogicalType.TIMESTAMP` 目前可能携带：

```text
MySQL JDBC  -> java.sql.Timestamp
Oracle JDBC -> 驱动厂商对象
XLSX       -> Long / Double / java.util.Date
CSV        -> LocalDateTime
```

这类值在同源同目标时可能碰巧可用，但在 XLSX -> MySQL、Oracle -> MySQL 等跨格式或异库任务中，Target 可能无法识别 Source 传来的对象，导致写入失败或静默丢失精度、时区。

核心问题不是缺少 `LogicalType`，而是：

```text
Schema 中的 LogicalType 与 DataRecord 中的 Java 类型没有统一契约。
```

## 2. 当前实现

当前 ETL 数据流如下：

```text
Source.schema() 生成 LogicalType
        |
Source.read() 读取原始对象
        |
DataRecord（不校验对象类型）
        |
Transformer / FieldMapping（只复制 Schema 和值）
        |
Target（自行尝试写入）
```

各组件当前行为：

| 组件 | LogicalType 来源 | 实际值处理 | 当前归一化程度 |
|---|---|---|---|
| `CsvSource` | 显式 Schema，或扫描最多 1000 行推断 | `convert()` 将部分文本转为 Java 类型 | 部分完成 |
| `XlsxSource` | 显式 Schema，或扫描最多 1000 行推断 | 通过 `LogicalTypeCatalog` 解码为标准 Java 类型 | 已完成 |
| `JdbcQuerySource` | `ResultSetMetaData.getColumnType()` | 通过 `LogicalTypeCatalog` 类型化读取 | 已完成 |
| `JdbcTableSource` | `DatabaseMetaData.getColumns()` | 委托已归一化的 `JdbcQuerySource` 读取 | 已完成 |
| `MemorySource` | 调用方传入 | 原样保存 | 未校验 |
| `CsvTarget` | Source Schema | `String.valueOf()` | Target 自行处理 |
| `XlsxTarget` | Source Schema | 原样交给 Hutool | Target 自行处理 |
| `JdbcTableTarget` | Source Schema | 无类型 `PreparedStatement.setObject()` | Target 自行处理 |
| `MemoryTarget` | Source Schema | 原样保存 | 未校验 |

已经确认的边界表现：

| 场景 | 当前结果 | 说明 |
|---|---|---|
| MySQL `jsh_depot_head` -> XLSX | 成功 | 当前表中的 JDBC 对象恰好能被 Hutool 接受，不代表类型契约正确 |
| XLSX 数字日期 -> MySQL `TIMESTAMP` | 失败 | XLSX 输出 `Number`，JDBC Target 使用 `setObject()` 后数据库拒绝 |

相关测试：

```text
JdbcToXlsxIntegrationTest
XlsxToMysqlBoundaryIntegrationTest
```

## 3. 目标设计

归一化边界放在 Source 和 Target，不放在 `EtlTask`：

```text
外部值 / 厂商对象
        |
Source Decoder：解码并归一化
        |
SchemaLoom 标准 Java 类型
        |
DataRecord：统一校验
        |
Transformer：只处理标准类型
        |
Target Encoder：按目标能力编码
        |
文件格式 / 目标数据库
```

原则：

1. Source 必须先把原始值转成标准类型，才能创建 `DataRecord`。
2. `DataRecord` 应校验非空值是否符合 `LogicalType` 契约。
3. Target 只接收标准类型，并负责转换成目标 API 支持的类型。
4. 不能无损转换时明确失败，不依赖驱动猜测，也不静默降级。
5. 错误包含字段名、逻辑类型和实际 Java 类名，不包含原始值，避免泄露数据。

## 4. 标准类型契约

| LogicalType | DataRecord 中的标准 Java 类型 |
|---|---|
| `BOOLEAN` | `Boolean` |
| `INT16` | `Short` |
| `INT32` | `Integer` |
| `INT64` | `Long` |
| `DECIMAL` | `BigDecimal` |
| `FLOAT32` | `Float` |
| `FLOAT64` | `Double` |
| `STRING` | `String` |
| `DATE` | `LocalDate` |
| `TIME` | `LocalTime` |
| `TIMESTAMP` | `LocalDateTime` |
| `BINARY` | `byte[]` |
| 拟新增 `OFFSET_TIME` | `OffsetTime` |
| 拟新增 `OFFSET_TIMESTAMP` | `OffsetDateTime` |

`null` 不需要转换；是否允许 `null` 继续由 `FieldSchema.isNullable()` 表达。

建议由公共校验器维护唯一映射：

```java
final class LogicalValues {
    static Class<?> javaType(LogicalType type);

    static void validate(FieldSchema field, Object value) {
        if (value == null) return;
        if (!javaType(field.getLogicalType()).isInstance(value)) {
            throw typeMismatch(field, value.getClass());
        }
    }
}
```

`DataRecord` 构造时逐字段调用 `LogicalValues.validate()`。这样后来新增的 Source 或 Transformer 如果传入厂商对象，会在进入 Target 前立即失败。

## 5. Source 如何归一化

### 5.1 CSV Source

CSV 的原始值都是文本，使用文本解码器：

```java
for each field:
    String text = csvRow.get(columnIndex);
    Object value = TextValueCodec.parse(field.logicalType, text);
    values.add(value);

emit new DataRecord(schema, values);
```

```java
DATE      -> LocalDate.parse(text)
TIMESTAMP -> LocalDateTime.parse(text)
DECIMAL   -> new BigDecimal(text)
INT32     -> Integer.valueOf(text)
BINARY    -> Base64 decode（需明确 CSV 格式约定）
```

现有 `CsvSource.convert()` 可以作为起点，但应补齐 `TIME`、浮点数、二进制和带时区类型，并将文本格式约定集中到 `TextValueCodec`。

### 5.2 XLSX Source

XLSX Source 根据 Schema 将 Hutool/POI 值转换成标准类型，不能直接保存单元格对象：

```java
Object raw = excelRow.get(columnIndex);
Object value = ExcelValueCodec.decode(field, raw);
values.add(value);
```

示例：

```java
TIMESTAMP + Excel serial number
    -> 按 Excel 日期系统转换
    -> LocalDateTime

INT32 + Number
    -> 检查无小数且不溢出
    -> Integer

DECIMAL + Number/String
    -> BigDecimal
```

转换必须检查溢出和精度，不能直接使用 `Number.intValue()` 截断数据。带时区时间在 Excel 中没有等价单元格类型，使用 ISO-8601 字符串表示。

### 5.3 JDBC Source

JDBC Source 根据列索引和 `LogicalType` 调用有明确类型的 getter：

```java
for each column index:
    FieldSchema field = schema.fields[index];
    Object value = JdbcValueCodec.read(resultSet, index + 1, field);
    values.add(value);
```

```java
switch (field.logicalType) {
    case DATE:
        Date value = rs.getDate(index);
        return value == null ? null : value.toLocalDate();

    case TIMESTAMP:
        Timestamp value = rs.getTimestamp(index);
        return value == null ? null : value.toLocalDateTime();

    case INT32:
        int value = rs.getInt(index);
        return rs.wasNull() ? null : Integer.valueOf(value);

    case OFFSET_TIMESTAMP:
        return rs.getObject(index, OffsetDateTime.class);
}
```

类型化 `getObject(index, StandardClass.class)` 可以用于 JDBC 没有专用 getter 的 JDBC 4.2 类型。禁止继续使用不指定目标类型的 `getObject(name)`。

未知 JDBC 类型按以下规则处理：

```text
JSON、UUID、枚举等标量 -> getString() -> LogicalType.STRING
ARRAY、STRUCT、REF 等复杂类型 -> 明确拒绝
```

## 6. Target 如何编码

Target 不要求外部系统理解 SchemaLoom 的标准对象，而是执行反向编码。

### 6.1 CSV Target

```java
LogicalValues.validate(field, value);
String text = TextValueCodec.format(field.logicalType, value);
csvWriter.write(escape(text));
```

日期和时间统一使用 ISO-8601；二进制使用明确约定的 Base64，不使用任意对象的 `toString()`。

### 6.2 XLSX Target

```java
DATE / TIMESTAMP -> 转成 Excel 日期单元格并设置格式
数值             -> 写数值单元格
STRING           -> 写字符串单元格
OFFSET_TIMESTAMP -> 写 ISO-8601 字符串
BINARY           -> Base64 字符串，或按配置拒绝
```

### 6.3 JDBC Target

JDBC Target 将标准值转回 JDBC 明确支持的参数类型：

```java
switch (field.logicalType) {
    case DATE:
        ps.setDate(index, java.sql.Date.valueOf((LocalDate) value));
        break;

    case TIMESTAMP:
        ps.setTimestamp(index, Timestamp.valueOf((LocalDateTime) value));
        break;

    case DECIMAL:
        ps.setBigDecimal(index, (BigDecimal) value);
        break;

    case BINARY:
        ps.setBytes(index, (byte[]) value);
        break;
}
```

空值使用目标 SQL 类型绑定：

```java
if (value == null) {
    ps.setNull(index, dialect.sqlType(field.logicalType));
    return;
}
```

禁止继续使用：

```java
ps.setObject(index, arbitraryValue);
```

带时区类型可以使用 JDBC 4.2 类型化 `setObject()`，但必须先检查目标数据库能力。

## 7. 数据库能力与无损原则

| LogicalType | MySQL | Oracle | SQL Server |
|---|---|---|---|
| `DATE` | `DATE` | `DATE` | `DATE` |
| `TIME` | `TIME` | 无独立等价类型 | `TIME` |
| `TIMESTAMP` | `DATETIME` | `TIMESTAMP` | `DATETIME2` |
| `OFFSET_TIMESTAMP` | 无原生等价类型 | `TIMESTAMP WITH TIME ZONE` | `DATETIMEOFFSET` |

`Target.prepare()` 必须通过方言检查目标能力：

```java
TargetType targetType = dialect.resolveType(field.logicalType);
if (!targetType.isSupported()) {
    throw unsupportedTargetType(databaseType, field);
}
```

默认策略是拒绝无法无损表达的类型。未来可以增加显式配置允许 ISO 字符串降级，但不能默认把带时区时间写成无时区 `DATETIME`。

## 8. 新 Source / Target 接入规则

新增 Source：

```text
1. 生成 RecordSchema 和 LogicalType
2. 为每个 LogicalType 定义外部值 -> 标准值转换
3. 转换完成后创建 DataRecord
4. 补齐 null、溢出、精度和不支持类型测试
```

新增 Target：

```text
1. 声明支持的 LogicalType 和能力限制
2. prepare 阶段拒绝不支持或有损的类型
3. 将标准值编码为目标格式
4. 禁止依赖任意对象的 toString() 或无类型 setObject()
5. 补齐标准值 -> 目标 -> 再读取的往返测试
```

## 9. 建议实施顺序

1. 建立 `LogicalType` 标准 Java 类型表和 `LogicalValues` 校验器。
2. 改造 `DataRecord`，在入口检查实际值类型。
3. 实现 `JdbcValueCodec`，改造 JDBC Source 和 Target。
4. 实现 `ExcelValueCodec`，修复 XLSX 数字日期写 MySQL 的现有失败测试。
5. 提取并补全 `TextValueCodec`，统一 CSV 的解析和格式化。
6. 增加带时区逻辑类型以及数据库方言能力检查。

第 7、8 项不属于当前项目范围，见“明确不支持的场景”。

验收标准：任意 `DataRecord` 的非空值都符合标准类型契约；任意 Target 只处理标准类型；不支持或有损的转换在写入前给出字段级错误。

## 10. 当前实现状态

截至 2026-08-13，当前代码已经完成：

| 能力 | 状态 | 说明 |
|---|---|---|
| 标准 Java 类型映射 | 已完成 | `LogicalValues` 集中维护并在初始化时检查是否覆盖全部 `LogicalType` |
| `DataRecord` 类型校验 | 已完成 | 创建记录时拒绝厂商对象和不匹配的 Java 类型 |
| CSV Source/Target | 已完成基础能力 | 支持标准标量、时间类型和 Base64 二进制 |
| XLSX Source/Target | 已完成基础能力 | 支持 Excel 日期序列、标准时间、ISO 字符串和 Base64 二进制 |
| JDBC Source/Target | 已完成基础能力 | 使用类型化读取和明确 setter；带时区类型使用 JDBC 4.2 API |
| 数据库能力检查 | 已完成 | 每个方言显式声明每个 `LogicalType` 的支持能力，`prepare` 阶段拒绝不支持类型 |
| 统一扩展约束 | 已完成 | `LogicalTypeCatalog` 覆盖全部类型；新增类型遗漏定义或方言映射会立即失败 |

因此，当前项目范围内的 LogicalType 数据归一化 feature 已完成。

## 10.1 明确不支持的场景

以下场景不在当前项目范围内，不作为本 feature 的待办事项：

| 场景 | 当前行为 |
|---|---|
| JDBC `TIME WITH TIME ZONE` / `TIMESTAMP WITH TIME ZONE` 的 Schema 自动推断 | 不保证保留时区语义；需要时由调用方提供显式 Schema |
| JDBC `ARRAY`、`STRUCT`、`REF` 等复杂对象 | 不支持；应在接入层转换为标量或字符串后再进入 SchemaLoom |
| 特定厂商 JDBC 驱动的全部类型矩阵 | 不保证；项目仅覆盖标准 JDBC API 和已有测试场景 |
| Memory Source/Target、Transformer 的独立类型契约测试 | 不单独提供；统一由 `DataRecord` 入口校验保证 |
| Excel 日期/时间显示样式定制 | 不保证；仅保证标准值写入和读回语义 |
| CSV/XLSX 的格式策略配置化 | 不支持；二进制固定使用 Base64，带时区时间固定使用 ISO-8601 文本 |

## 11. 新增 LogicalType 的维护规则

以新增 `TEXT` 为例，不能只修改 `LogicalType` 枚举。必须按以下顺序完成：

1. 在 `LogicalValues` 增加 `TEXT -> 标准 Java 类型`，并补 `LogicalValuesTest`。
2. 在 `TextValueCodec` 增加文本解析和格式化规则；明确它与 `STRING` 的区别、长度和空值语义。
3. 在 `ExcelValueCodec` 增加 XLSX 读写规则；不能支持时显式拒绝，不能落入普通 `default` 分支。
4. 在 `JdbcValueCodec` 增加 JDBC 读取规则，并在 `JdbcTypes` 声明 JDBC 类型到 `TEXT` 的映射。
5. 在每个数据库方言声明建表类型、参数绑定类型和是否支持无损写入。
6. 在每个 Target 声明是否支持该类型，并在 `prepare` 阶段拒绝不支持或有损转换。
7. 增加 CSV、XLSX、JDBC 以及至少一个真实数据库的往返测试。
8. 更新本表和 README 的能力说明。

核心映射集中在 `LogicalValues`，但 Source/Target 的 `switch` 仍然是必要的适配层：同一个逻辑类型在 JDBC、Excel、CSV 和不同数据库中的外部 API 不同。适配层禁止使用“未知类型按字符串处理”的默认降级；新增类型遗漏时应直接失败或由测试暴露。

## 12. 新 Source / Target 开发模板

### 新 Source

```text
1. schema() 只产生 LogicalType，不把厂商 Java 类暴露给下游。
2. read() 逐字段调用本 Source 的 Decoder，转换完成后再创建 DataRecord。
3. Decoder 明确处理 null、溢出、精度、时区和不支持类型。
4. 增加“原始值 -> DataRecord 标准值”的单元测试。
5. 增加 Source -> MemoryTarget 的最小集成测试。
```

### 新 Target

```text
1. prepare() 保存 Schema，并先检查每个 LogicalType 的能力。
2. write() 只接收 DataRecord 标准类型，按字段调用 Encoder。
3. Encoder 不使用任意对象 toString()，也不使用无目标类型的 setObject()。
4. 不支持或有损的类型在 prepare() 失败，而不是写入中途失败。
5. 增加“标准值 -> 目标文件/数据库 -> Source 读回”的往返测试。
```

### 适配层的设计边界

`LogicalValues` 负责跨系统不变的标准类型契约；`TextValueCodec`、`ExcelValueCodec`、`JdbcValueCodec` 和数据库方言负责外部系统差异。不要把所有格式的转换继续堆进 `DataRecord`，也不要为了消除所有 `switch` 而引入一个隐藏默认转换表。新增类型必须显式声明每个边界是否支持。

## 13. 强制扩展约束

类型能力的唯一注册入口是 `LogicalTypeCatalog`。每一个 `LogicalTypeDefinition` 构造时必须提供以下能力：

```text
标准 Java 类型
文本 parse / format
XLSX decode / encode
JDBC read / write / SQL NULL 类型
```

因此新增 `LogicalType` 后，如果没有在 `LogicalTypeCatalog` 完整注册，Catalog 初始化会因枚举覆盖检查失败；无法只依赖某个格式的 `default` 分支继续运行。`TextValueCodec`、`ExcelValueCodec`、`JdbcValueCodec` 只是兼容门面，统一委托 Catalog，Source 和 Target 不再自行解释逻辑类型。

数据库能力的唯一入口是 `DatabaseDialect.mapping(LogicalType)`。每个方言都必须返回 `DatabaseTypeMapping`：支持时声明 DDL 类型，不支持时显式返回 `unsupported()`。`JdbcTableTarget.prepare()` 会先执行能力检查；不能无损表示的类型在写入前以字段级错误拒绝。

对应测试：

```text
LogicalValuesTest：Catalog 覆盖每个 LogicalType，并定义 JDBC NULL 类型。
DialectTest：每个 DatabaseDialect 为每个 LogicalType 声明能力。
```

新增 Source 或 Target 时不得新增按 `LogicalType` 分支的业务代码。它们应调用 `LogicalTypeCatalog.get(field.getLogicalType())` 的 decode/encode/read/write 方法；新格式特有的表示规则应通过新的类型定义接口加入 Catalog。这样遗漏会由接口构造、Catalog 覆盖检查或能力测试暴露，而不是依赖开发者记忆维护清单。
