### 目前异库ETL其实仅为基本可用程度，还没做到健壮和实用的地步，并且即使是同库的小众特性的数据读写容易出问题
- 布尔值：true/false、1/0、Y/N
- 时间：本地时间、UTC、数据库 Session Time Zone
- Decimal：精度、Scale、舍入方式
- 数字：有符号范围、溢出
- 字符串：字符集、Unicode、长度单位是字节还是字符
- 二进制：byte[]、BLOB、Base64、驱动 LOB 对象
- NULL：默认值是否生效、空字符串是否等于 NULL
- 自增字段：是否写入源值，还是让目标库重新生成；Oracle Sequence / Identity
- 生成列：不能直接写入，必须排除
- target写入应考虑校验：主键、索引、外键，然后决定跳过、删除或重建。；当前项目的索引比较只比较唯一性和列名，无法识别索引类型差异。
- 外键应为多表/库级别任务，并延后创建

### 如果需要完善的schema全局规则应考虑如下总体流程：
```
1. CapabilityProbe
   探测源/目标能力

2. MigrationPlan
   生成字段、表、索引、外键、时区和大字段策略

3. DdlExecutor
   按依赖顺序创建目标结构

4. DataConverter
   对每列执行值转换、范围校验、LOB 策略

5. Validator
   行数、主键、NULL、精度、哈希、约束完整性校验
```
其中 MigrationPlan 应提前输出风险，而不是等运行时失败：
```
字段 amount:
DECIMAL(65,30) -> NUMBER(38,30)
风险：源精度超过目标上限

字段 content:
CLOB -> NVARCHAR(MAX)
风险：目标驱动不支持流式写入

字段 created_at:
TIMESTAMP WITH TIME ZONE -> DATETIME
风险：时区信息丢失
```