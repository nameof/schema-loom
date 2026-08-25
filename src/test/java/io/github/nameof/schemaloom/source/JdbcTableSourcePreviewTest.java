package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.driver.DatabaseType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JdbcTableSourcePreviewTest {
    private JdbcTableSource source() {
        return new JdbcTableSource(new DatabaseConnectionInfo(
                DatabaseType.MYSQL, "localhost", 3306, "db", "user", null), "orders");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPreviewRowsAboveHardLimitBeforeOpeningConnection() {
        source().preview(301);
    }

    @Test
    public void exposesStablePreviewLimits() {
        assertEquals(300, JdbcTableSource.MAX_PREVIEW_ROWS);
    }
}
