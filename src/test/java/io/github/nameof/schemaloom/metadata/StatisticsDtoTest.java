package io.github.nameof.schemaloom.metadata;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StatisticsDtoTest {
    @Test public void exposesTableStatisticsInBytes() {
        TableStatistics statistics = new TableStatistics(12, 1024, 512, 85);
        assertEquals(12, statistics.getRowCount());
        assertEquals(1024, statistics.getDataLength());
        assertEquals(512, statistics.getIndexLength());
        assertEquals(85, statistics.getAvgRowLength());
    }

    @Test public void exposesSchemaStatistics() {
        SchemaStatistics statistics = new SchemaStatistics(3, 12, 2048);
        assertEquals(3, statistics.getTotalTables());
        assertEquals(12, statistics.getTotalRows());
        assertEquals(2048, statistics.getTotalDataLength());
    }
}
