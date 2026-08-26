package io.github.nameof.schemaloom.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 写入阶段的累计统计。 */
public final class WriteStatistics {
    private final long writtenRows;
    private final long skippedRows;
    private final long failedRows;
    private final Map<String, Long> skippedFieldRows;

    public WriteStatistics(long writtenRows, long skippedRows, long failedRows, Map<String, Long> skippedFieldRows) {
        if (writtenRows < 0 || skippedRows < 0 || failedRows < 0) throw new IllegalArgumentException("统计值不能为负数");
        this.writtenRows = writtenRows;
        this.skippedRows = skippedRows;
        this.failedRows = failedRows;
        this.skippedFieldRows = immutableCounts(skippedFieldRows);
    }

    public static WriteStatistics empty() { return new WriteStatistics(0, 0, 0, Collections.<String, Long>emptyMap()); }
    public long getWrittenRows() { return writtenRows; }
    public long getSkippedRows() { return skippedRows; }
    public long getFailedRows() { return failedRows; }
    public Map<String, Long> getSkippedFieldRows() { return skippedFieldRows; }

    private static Map<String, Long> immutableCounts(Map<String, Long> counts) {
        Map<String, Long> copy = new LinkedHashMap<String, Long>();
        if (counts != null) for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0)
                throw new IllegalArgumentException("字段跳过统计无效");
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}
