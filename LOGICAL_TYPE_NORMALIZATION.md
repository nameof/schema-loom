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
| `XlsxSource` | 显式 Schema，或扫描最多 1000 行推断 | Hutool 返回什么就保存什么 | 未完成 |
| `JdbcQuerySource` | `ResultSetMetaData.getColumnType()` | 无类型 `ResultSet.getObject()` | 未完成 |
| `JdbcTableSource` | `DatabaseMetaData.getColumns()` | 委托 `JdbcQuerySource` 读取 | 未完成 |
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
7. 为 Memory Source/Target 和 Transformer 增加契约测试。
8. 建立 MySQL、Oracle、SQL Server 的真实驱动类型矩阵和往返集成测试。

验收标准：任意 `DataRecord` 的非空值都符合标准类型契约；任意 Target 只处理标准类型；不支持或有损的转换在写入前给出字段级错误。
