package io.github.nameof.schemaloom.driver;

import org.junit.Test;
import static org.junit.Assert.*;

public class JdbcDriverLoaderTest {
    @Test public void acceptsInclusiveAndExclusiveVersionRanges() {
        assertTrue(JdbcDriverLoader.VersionRange.accept("[5.7,8.0)", "5.7.44"));
        assertTrue(JdbcDriverLoader.VersionRange.accept("[5.7,8.0)", "7.9.99"));
        assertFalse(JdbcDriverLoader.VersionRange.accept("[5.7,8.0)", "8.0.0"));
        assertFalse(JdbcDriverLoader.VersionRange.accept("[5.7,8.0)", "5.6.9"));
        assertTrue(JdbcDriverLoader.VersionRange.accept("[,)", "anything"));
    }
}
