# 大字段处理现状说明

## 1. 当前定位

大字段处理策略配置在 JDBC Source 中，由 `JdbcQuerySource` 和 `JdbcTableSource` 决定字段值是否读取。
`EtlTask` 不判断 BLOB、CLOB、TEXT、LONGTEXT 等数据库类型，只负责收集 Source 和 Target 提供的通用统计。

Target 不需要理解大字段策略。Source 跳过字段时仍保留字段 Schema，并向 Target 提供 `null`。

## 2. 已实现能力

### 2.1 Source 策略配置

通过 `LargeFieldPolicy` 配置字段处理动作：

```java
LargeFieldPolicy policy = LargeFieldPolicy.builder()
    .field("attachment", LargeFieldPolicy.Action.SKIP)
    .typeName("LONGTEXT", LargeFieldPolicy.Action.SKIP)
    .maxBinaryBytes(8L * 1024 * 1024)
    .maxTextChars(1024 * 1024)
    .build();
```

规则优先级为：

1. 字段名规则
2. 原生类型名规则
3. JDBC 类型规则
4. 默认动作

当前动作：

- `COPY`：读取并复制字段值。
- `SKIP`：不读取字段值，输出 `null`。

默认策略为 `BLOB` 跳过，`CLOB`、`TEXT`、`LONGTEXT` 复制。用户仍可按字段名、原生类型名或 JDBC 类型覆盖该默认行为。

### 2.2 阈值处理

可以配置二进制字节阈值和文本字符阈值。超过阈值时，当前行为是：

- 字段输出 `null`；
- 读取统计中增加该字段的跳过计数；
- 当前行仍继续进入后续转换和写入流程。

对 JDBC `BLOB`、`CLOB`，配置对应阈值时会先检查 LOB 长度，再决定是否物化实际内容，以减少超限值进入应用内存的机会。

### 2.3 Schema 和目标表行为

只要策略可能返回 `null`，Source 对外暴露的字段就会标记为可空。目标表结构仍保留该字段。

- 新建目标表：按可空字段创建，可写入 `null`。
- 追加到已有目标表：如果目标列为 `NOT NULL` 且没有默认值，`prepare` 阶段会拒绝。

## 3. 统计能力

### 3.1 通用统计对象

- `ReadStatistics`
  - `readRows`：Source 正式读取的行数。
  - `skippedRows`：Source 整行跳过数。
  - `skippedFieldRows`：按字段名统计的字段置空行数。
- `WriteStatistics`
  - `writtenRows`：成功写入行数。
  - `skippedRows`：Target 整行跳过数。
  - `failedRows`：写入失败行数。
  - `skippedFieldRows`：按字段名统计的字段跳过行数。

`ReadStatisticsProvider` 和 `WriteStatisticsProvider` 是可选能力接口，不改变既有 `Source`、`Target` 方法签名。

### 3.2 EtlResult 汇总

`EtlResult` 提供：

```java
result.getReadStatistics();
result.getWriteStatistics();
result.getTotalSkippedRows();
```

`getTotalSkippedRows()` 只汇总 Source 和 Target 的整行跳过数，不包含字段置空数，避免同一行被重复计算。

## 4. 尚未完成的工作

### 4.1 真正的流式复制

当前尚未支持 `STREAM` 动作。现有 `DataRecord` 会跨越 Source 批次回调、Transformer 和 Target 写入，因此不能安全地携带依赖 `ResultSet` 生命周期的 `InputStream` 或 `Reader`。

后续需要在内部执行层增加同一 `ResultSet` 生命周期内的读取和 JDBC 参数绑定契约，同时保证公共 API 不暴露 JDBC 或 Spring 类型。

### 4.2 超阈值拒绝和整行跳过

当前超阈值只会将字段置为 `null`，还未支持：

- 拒绝当前行并继续；
- 按 `ErrorPolicy` 处理超限行；
- 超限后转为流式处理；
- 在 `ReadStatistics.skippedRows` 中累计整行跳过数。

### 4.3 写入统计完整接入

`JdbcTableTarget` 已提供基础 `WriteStatistics`，但批量写入失败、逐行隔离、跳过批次等路径尚未全部映射到：

- `skippedRows`；
- `failedRows`；
- `skippedFieldRows`。

### 4.4 跨数据库类型归一化

不同数据库对 `TEXT`、`LONGTEXT`、`CLOB`、长二进制类型的 JDBC 类型和原生类型名返回可能不同。当前主要依赖 JDBC 类型和 `getColumnTypeName()`，还需要在方言层建立统一的类型归一化和契约测试。

### 4.5 实时进度统计

当前大字段统计在任务结束时写入 `EtlResult`，`EtlProgress` 尚未提供字段跳过数和整行跳过数。

## 5. 测试现状

当前已有 H2 测试覆盖：

- BLOB 默认跳过，CLOB 默认复制；
- 字段规则覆盖类型规则；
- 文本阈值超限后置空；
- `EtlResult` 收集读取统计。

仍需补充：

- MySQL、Oracle、SQL Server 的真实 LOB 行为；
- 超阈值拒绝和整行统计；
- JDBC Target 各种失败策略下的写入统计；
- 真正流式复制的驱动兼容性和资源释放。

## 6. 当前结论

当前版本适合使用“跳过大字段”或“受阈值限制复制”的场景，不应将 `COPY` 误解为无界安全复制，也不应将现有实现称为真正的流式复制。

推荐后续顺序：

1. 完善 `WriteStatistics` 和整行跳过统计。
2. 增加超阈值 `REJECT` 动作及错误策略集成。
3. 建立跨数据库类型归一化规则和集成测试。
4. 最后改造内部执行层，实现不暴露 JDBC 类型的真正流式复制。
