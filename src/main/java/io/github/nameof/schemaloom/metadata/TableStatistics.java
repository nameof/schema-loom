package io.github.nameof.schemaloom.metadata;

/** 表的低开销系统统计信息，长度单位为字节。 */
public final class TableStatistics {
    private final long rowCount, dataLength, indexLength, avgRowLength;

    public TableStatistics(long rowCount, long dataLength, long indexLength, long avgRowLength) {
        this.rowCount = rowCount;
        this.dataLength = dataLength;
        this.indexLength = indexLength;
        this.avgRowLength = avgRowLength;
    }

    /** 行数可能是估算值；0 也可能表示未收集统计或当前账号无权读取。 */
    public long getRowCount() { return rowCount; }
    /** 0 可能表示真实为空、引擎不提供、统计未收集或系统视图无权限。 */
    public long getDataLength() { return dataLength; }
    /** 0 可能表示没有索引、引擎不提供、统计未收集或系统视图无权限。 */
    public long getIndexLength() { return indexLength; }
    /** 0 可能表示真实为零、统计未收集、引擎不提供或系统视图无权限。 */
    public long getAvgRowLength() { return avgRowLength; }
}
