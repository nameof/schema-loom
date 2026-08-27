package io.github.nameof.schemaloom.metadata;

/** Schema 的低开销系统统计信息，长度单位为字节。 */
public final class SchemaStatistics {
    private final long totalTables, totalRows, totalDataLength;

    public SchemaStatistics(long totalTables, long totalRows, long totalDataLength) {
        this.totalTables = totalTables;
        this.totalRows = totalRows;
        this.totalDataLength = totalDataLength;
    }

    /** 只统计普通表；0 也可能表示 Schema 为空或当前账号不可见。 */
    public long getTotalTables() { return totalTables; }
    /** 行数可能是估算值；统计未收集时数据库通常返回 NULL，本 API 映射为 0。 */
    public long getTotalRows() { return totalRows; }
    /** 不包含索引长度；0 可能表示未收集、引擎不提供或系统视图无权限。 */
    public long getTotalDataLength() { return totalDataLength; }
}
