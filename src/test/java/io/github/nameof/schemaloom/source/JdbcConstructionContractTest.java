package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.driver.DatabaseType;
import io.github.nameof.schemaloom.driver.JdbcDriverLoader;
import io.github.nameof.schemaloom.target.JdbcTableTarget;
import org.junit.Test;

import java.nio.file.Files;
import java.util.Collections;

public class JdbcConstructionContractTest {
    private final DatabaseConnectionInfo info = new DatabaseConnectionInfo(
            DatabaseType.MYSQL, "invalid-host", 3306, "invalid-database", "user", "password");

    @Test(expected = IllegalArgumentException.class)
    public void invalidQueryIsRejectedBeforeDriverLookup() throws Exception {
        new JdbcQuerySource(info, "DELETE FROM orders", Collections.<Object>emptyList(), 100,
                new JdbcDriverLoader(Files.createTempDirectory("schemaloom-no-drivers")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidFetchSizeIsRejectedBeforeDriverLookup() throws Exception {
        new JdbcQuerySource(info, "SELECT * FROM orders", Collections.<Object>emptyList(), 0,
                new JdbcDriverLoader(Files.createTempDirectory("schemaloom-no-drivers")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidTableNameIsRejectedBeforeDriverLookup() throws Exception {
        new JdbcTableTarget(info, " ", new JdbcDriverLoader(Files.createTempDirectory("schemaloom-no-drivers")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void tableSourceRejectsInvalidFetchSizeWithoutOpeningConnection() throws Exception {
        new JdbcTableSource(info, "orders", new JdbcDriverLoader(Files.createTempDirectory("schemaloom-no-drivers")), 0);
    }
}
