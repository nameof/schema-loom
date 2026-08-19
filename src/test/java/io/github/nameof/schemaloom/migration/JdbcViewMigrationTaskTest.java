package io.github.nameof.schemaloom.migration;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import org.junit.Test;

import static org.junit.Assert.*;

public class JdbcViewMigrationTaskTest {
    @Test public void reportsSuccessOnlyAfterMigration() {
        EtlResult result = new JdbcViewMigrationTask(database(DatabaseType.MYSQL), database(DatabaseType.MYSQL),
                "source_view", "target_view").run();
        assertEquals(EtlStatus.FAILED, result.getStatus());
        assertEquals(1, result.getFailed());
        assertEquals("view-migration", result.getErrors().get(0).getStage());
    }

    @Test public void reportsValidationFailureWithoutThrowing() {
        EtlResult result = new JdbcViewMigrationTask(database(DatabaseType.MYSQL), database(DatabaseType.ORACLE),
                "source_view", "target_view").run();
        assertEquals(EtlStatus.FAILED, result.getStatus());
        assertFalse(result.getErrors().isEmpty());
    }

    @Test public void implementsCallableTaskContract() throws Exception {
        JdbcViewMigrationTask task = new JdbcViewMigrationTask(database(DatabaseType.MYSQL), database(DatabaseType.ORACLE),
                "source_view", "target_view");
        assertEquals(EtlStatus.FAILED, task.call().getStatus());
    }

    @Test public void rejectsInvalidConstructionBeforeExecution() {
        try {
            new JdbcViewMigrationTask(database(DatabaseType.MYSQL), database(DatabaseType.ORACLE), null, "target_view");
            fail("expected invalid source view");
        } catch (IllegalArgumentException expected) {
            // Configuration errors are reported before a task is created.
        }
    }

    private DatabaseConnectionInfo database(DatabaseType type) {
        return new DatabaseConnectionInfo(type, "host", 1, "database", "user", "password");
    }
}
