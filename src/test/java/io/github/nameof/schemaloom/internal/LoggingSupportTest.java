package io.github.nameof.schemaloom.internal;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LoggingSupportTest {
    @Test
    public void sanitizesCredentialsAndKeepsBoundedMessage() {
        String value = LoggingSupport.message("password=secret token:abc123 ordinary");
        assertFalse(value.contains("secret"));
        assertFalse(value.contains("abc123"));
        assertTrue(value.contains("<redacted>"));
    }

    @Test
    public void copiesSanitizedExceptionChainAndStack() {
        IllegalStateException cause = new IllegalStateException("pwd=inner-secret");
        IllegalArgumentException error = new IllegalArgumentException("password=outer-secret", cause);
        Throwable safe = LoggingSupport.safe(error);
        assertNotNull(safe);
        assertFalse(safe.toString().contains("outer-secret"));
        assertNotNull(safe.getCause());
        assertFalse(safe.getCause().toString().contains("inner-secret"));
        assertTrue(safe.getStackTrace().length > 0);
    }

    @Test
    public void createsShortRunId() {
        String runId = LoggingSupport.runId();
        assertTrue(runId.matches("[0-9a-f]{12}"));
    }
}
