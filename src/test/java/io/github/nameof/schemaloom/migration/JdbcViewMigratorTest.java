package io.github.nameof.schemaloom.migration;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class JdbcViewMigratorTest {

    /** D4 回归：前一个资源关闭抛异常时，后续资源仍必须被关闭，不能被遮蔽。 */
    @Test
    public void closeFailureDoesNotPreventClosingRemainingResources() {
        List<String> closed = new ArrayList<>();
        AutoCloseable failingTarget = () -> {
            throw new IllegalStateException("target close failed");
        };
        AutoCloseable source = () -> closed.add("source");

        JdbcViewMigrator.closeQuietly(failingTarget);
        JdbcViewMigrator.closeQuietly(source);

        assertEquals(Collections.singletonList("source"), closed);
    }

    @Test
    public void ignoresNullResource() {
        JdbcViewMigrator.closeQuietly(null);
    }
}
