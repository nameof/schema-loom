package io.github.nameof.schemaloom.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 读取阶段的累计统计。 */
public final class ReadStatistics {
    private final long readRows;
    private final long skippedRows;
    private final Map<String, Long> skippedFieldRows;

    public ReadStatistics(long readRows, long skippedRows, Map<String, Long> skippedFieldRows) {
        if (readRows < 0 || skippedRows < 0) throw new IllegalArgumentException("统计值不能为负数");
        this.readRows = readRows;
        this.skippedRows = skippedRows;
        this.skippedFieldRows = immutableCounts(skippedFieldRows);
    }

    public static ReadStatistics empty() { return new ReadStatistics(0, 0, Collections.<String, Long>emptyMap()); }
    public long getReadRows() { return readRows; }
    public long getSkippedRows() { return skippedRows; }
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
